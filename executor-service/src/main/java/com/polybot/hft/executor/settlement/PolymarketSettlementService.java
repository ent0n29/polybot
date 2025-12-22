package com.polybot.hft.executor.settlement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.executor.sim.PaperExchangeSimulator;
import com.polybot.hft.polymarket.auth.PolymarketAuthContext;
import com.polybot.hft.polymarket.data.PolymarketDataApiClient;
import com.polybot.hft.polymarket.data.PolymarketPosition;
import com.polybot.hft.polymarket.onchain.ContractConfig;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class PolymarketSettlementService {

  private static final TypeReference<List<PolymarketPosition>> POSITIONS_LIST = new TypeReference<>() {
  };

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketAuthContext authContext;
  private final @NonNull PolymarketDataApiClient dataApiClient;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull PaperExchangeSimulator simulator;
  private final @NonNull ExecutorSettlementProperties settlementProperties;
  private final @NonNull ProxyWalletFactoryTxSender txSender;
  private final @NonNull MarketEndTimeCache marketEndTimeCache;

  private final AtomicBoolean inFlight = new AtomicBoolean(false);

  public List<SettlementAction> plan() {
    if (simulator.enabled()) {
      log.debug("settlement plan: simulator enabled, skipping");
      return List.of();
    }
    String maker = makerAddress();
    if (maker == null) {
      log.warn("settlement plan: no maker address available (auth not configured?)");
      return List.of();
    }

    log.debug("settlement plan: fetching positions for maker={}", maker);
    JsonNode raw = dataApiClient.getPositions(maker, 500, 0);
    List<PolymarketPosition> positions = objectMapper.convertValue(raw, POSITIONS_LIST);

    int redeemable = 0;
    int mergeable = 0;
    for (PolymarketPosition p : positions) {
      if (p != null) {
        if (Boolean.TRUE.equals(p.redeemable())) redeemable++;
        if (Boolean.TRUE.equals(p.mergeable())) mergeable++;
      }
    }
    log.debug("settlement plan: found {} positions ({} redeemable, {} mergeable)",
        positions.size(), redeemable, mergeable);

    return planFromPositions(positions);
  }

  public SettlementRunResult runOnce(Boolean dryRunOverride) {
    if (simulator.enabled()) {
      return new SettlementRunResult(false, true, "simulator enabled", List.of(), List.of());
    }
    if (properties.risk().killSwitch()) {
      return new SettlementRunResult(false, true, "kill-switch enabled", List.of(), List.of());
    }
    if (properties.mode() != HftProperties.TradingMode.LIVE) {
      return new SettlementRunResult(false, true, "not in LIVE mode", List.of(), List.of());
    }

    boolean dryRun = dryRunOverride != null ? dryRunOverride : settlementProperties.dryRun();

    if (!inFlight.compareAndSet(false, true)) {
      return new SettlementRunResult(false, dryRun, "in-flight", List.of(), List.of());
    }
    try {
      List<SettlementAction> actions = plan();
      if (actions.isEmpty()) {
        return new SettlementRunResult(true, dryRun, "no-actions", actions, List.of());
      }

      if (dryRun) {
        log.info("settlement dry-run: plannedActions={}", actions.size());
        for (SettlementAction a : actions) {
          log.info("  {}", a.summary());
        }
        return new SettlementRunResult(true, true, "dry-run", actions, List.of());
      }

      List<SettlementTxResult> txs = new ArrayList<>();
      for (SettlementAction action : actions) {
        SettlementTxResult result = executeActionWithRetry(action);
        txs.add(result);
      }
      return new SettlementRunResult(true, false, "executed", actions, txs);
    } finally {
      inFlight.set(false);
    }
  }

  private SettlementTxResult executeActionWithRetry(SettlementAction action) {
    int maxRetries = settlementProperties.maxRetries() != null ? settlementProperties.maxRetries() : 3;
    Exception lastError = null;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        if (attempt > 0) {
          long backoffMs = (long) (1000 * Math.pow(2, attempt - 1)); // 1s, 2s, 4s
          log.info("retrying settlement action (attempt {}/{}): {}", attempt + 1, maxRetries + 1, action.summary());
          Thread.sleep(backoffMs);
        }
        executeAction(action);
        return SettlementTxResult.submitted(action);
      } catch (Exception e) {
        lastError = e;
        log.warn("settlement action failed (attempt {}/{}): {} err={}",
            attempt + 1, maxRetries + 1, action.summary(), e.toString());
      }
    }

    log.error("settlement action failed after {} retries: {} err={}",
        maxRetries, action.summary(), lastError != null ? lastError.toString() : "unknown");
    return SettlementTxResult.failed(action, lastError != null ? lastError.toString() : "unknown error");
  }

  private void executeAction(SettlementAction action) throws Exception {
    ContractConfig contracts = ContractConfig.forChainId(properties.polymarket().chainId());
    String conditionalTokens = contracts.conditionalTokens();
    String collateral = contracts.collateral();

    String calldata;
    if (action.type() == SettlementActionType.REDEEM) {
      calldata = ConditionalTokensCallEncoder.encodeRedeemPositions(
          collateral,
          action.conditionId(),
          action.indexSets()
      );
    } else if (action.type() == SettlementActionType.MERGE) {
      calldata = ConditionalTokensCallEncoder.encodeMergePositions(
          collateral,
          action.conditionId(),
          action.indexSets(),
          action.mergeAmountBaseUnits()
      );
    } else {
      throw new IllegalArgumentException("Unknown action type: " + action.type());
    }

    String factoryCalldata = ProxyWalletFactoryCallEncoder.encodeProxy(
        List.of(ProxyWalletFactoryCallEncoder.ProxyCall.call(conditionalTokens, calldata))
    );

    txSender.sendFactoryProxyTx(factoryCalldata);
  }

  private List<SettlementAction> planFromPositions(List<PolymarketPosition> positions) {
    if (positions == null || positions.isEmpty()) {
      return List.of();
    }

    int decimals = ContractConfig.forChainId(properties.polymarket().chainId()).conditionalTokenDecimals();
    BigInteger minMergeAmount = toBaseUnits(settlementProperties.minMergeShares(), decimals);

    Map<String, List<PolymarketPosition>> byCondition = new HashMap<>();
    for (PolymarketPosition p : positions) {
      if (p == null || p.conditionId() == null || p.conditionId().isBlank()) {
        continue;
      }
      if (Boolean.TRUE.equals(p.negativeRisk())) {
        continue;
      }
      byCondition.computeIfAbsent(p.conditionId(), ignored -> new ArrayList<>()).add(p);
    }

    List<SettlementAction> actions = new ArrayList<>();
    for (Map.Entry<String, List<PolymarketPosition>> entry : byCondition.entrySet()) {
      String conditionId = entry.getKey();
      List<PolymarketPosition> ps = entry.getValue();
      if (ps == null || ps.isEmpty()) {
        continue;
      }

      boolean redeemable = ps.stream().anyMatch(p -> Boolean.TRUE.equals(p.redeemable()));
      List<BigInteger> indexSets = computeIndexSets(ps);

      String slug = firstNonBlank(ps, PolymarketPosition::slug);
      String title = firstNonBlank(ps, PolymarketPosition::title);

      if (redeemable) {
        actions.add(SettlementAction.redeem(conditionId, slug, title, indexSets));
        continue;
      }

      boolean mergeable = ps.stream().anyMatch(p -> Boolean.TRUE.equals(p.mergeable()));
      if (!mergeable) {
        continue;
      }

      // Check if we should merge based on market end time
      if (settlementProperties.mergeOnlyNearEnd()) {
        Instant endTime = marketEndTimeCache.getEndTime(conditionId);
        if (endTime != null) {
          long secondsToEnd = java.time.Duration.between(Instant.now(), endTime).getSeconds();
          long mergeWindow = settlementProperties.mergeSecondsBeforeEnd();
          if (secondsToEnd > mergeWindow) {
            // Market not ending soon enough, skip merge
            continue;
          }
        }
      }

      // Mergeable amount is limited by the smallest leg size across outcomes (complete set).
      BigInteger mergeAmount = minSizeBaseUnits(ps, decimals);
      if (mergeAmount == null || mergeAmount.signum() <= 0) {
        continue;
      }
      if (mergeAmount.compareTo(minMergeAmount) < 0) {
        continue;
      }

      actions.add(SettlementAction.merge(conditionId, slug, title, indexSets, mergeAmount, decimals));
    }

    // Redeem first (free collateral), then merge.
    actions.sort(Comparator
        .comparing((SettlementAction a) -> a.type() == SettlementActionType.REDEEM ? 0 : 1)
        .thenComparing(SettlementAction::slug, Comparator.nullsLast(String::compareTo)));

    return actions;
  }

  private String makerAddress() {
    String funder = authContext.funderAddress().orElse(null);
    if (funder != null && !funder.isBlank()) {
      return funder;
    }
    return authContext.signerCredentials()
        .map(Credentials::getAddress)
        .orElse(null);
  }

  private static List<BigInteger> computeIndexSets(List<PolymarketPosition> positions) {
    SortedSet<Integer> idxs = new TreeSet<>();
    for (PolymarketPosition p : positions) {
      if (p != null && p.outcomeIndex() != null && p.outcomeIndex() >= 0 && p.outcomeIndex() < 256) {
        idxs.add(p.outcomeIndex());
      }
    }
    if (idxs.isEmpty()) {
      return List.of(BigInteger.ONE, BigInteger.TWO);
    }
    List<BigInteger> out = new ArrayList<>(idxs.size());
    for (Integer i : idxs) {
      out.add(BigInteger.ONE.shiftLeft(i));
    }
    return out;
  }

  private static BigInteger minSizeBaseUnits(List<PolymarketPosition> positions, int decimals) {
    BigInteger min = null;
    for (PolymarketPosition p : positions) {
      if (p == null || p.size() == null) {
        continue;
      }
      BigInteger size = toBaseUnits(p.size(), decimals);
      if (size.signum() <= 0) {
        continue;
      }
      min = (min == null) ? size : min.min(size);
    }
    return min;
  }

  private static BigInteger toBaseUnits(BigDecimal v, int decimals) {
    if (v == null) {
      return BigInteger.ZERO;
    }
    BigDecimal scaled = v.movePointRight(decimals);
    return scaled.setScale(0, RoundingMode.DOWN).toBigInteger();
  }

  private static String firstNonBlank(List<PolymarketPosition> positions, java.util.function.Function<PolymarketPosition, String> extractor) {
    for (PolymarketPosition p : positions) {
      if (p == null) {
        continue;
      }
      String v = extractor.apply(p);
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  public enum SettlementActionType {
    REDEEM,
    MERGE
  }

  public record SettlementAction(
      SettlementActionType type,
      String conditionId,
      String slug,
      String title,
      List<BigInteger> indexSets,
      BigInteger mergeAmountBaseUnits,
      BigDecimal mergeAmountShares
  ) {
    static SettlementAction redeem(String conditionId, String slug, String title, List<BigInteger> indexSets) {
      return new SettlementAction(SettlementActionType.REDEEM, conditionId, slug, title, indexSets, null, null);
    }

    static SettlementAction merge(String conditionId, String slug, String title, List<BigInteger> indexSets, BigInteger mergeAmountBaseUnits, int decimals) {
      BigDecimal shares = new BigDecimal(mergeAmountBaseUnits).movePointLeft(decimals);
      return new SettlementAction(SettlementActionType.MERGE, conditionId, slug, title, indexSets, mergeAmountBaseUnits, shares);
    }

    String summary() {
      if (type == SettlementActionType.REDEEM) {
        return "REDEEM condition=" + conditionId + " slug=" + slug;
      }
      return "MERGE condition=" + conditionId + " slug=" + slug + " shares=" + mergeAmountShares;
    }
  }

  public record SettlementTxResult(
      SettlementAction action,
      boolean submitted,
      String error
  ) {
    static SettlementTxResult submitted(SettlementAction action) {
      return new SettlementTxResult(action, true, null);
    }

    static SettlementTxResult failed(SettlementAction action, String error) {
      return new SettlementTxResult(action, false, error);
    }
  }

  public record SettlementRunResult(
      boolean ok,
      boolean dryRun,
      String status,
      List<SettlementAction> plannedActions,
      List<SettlementTxResult> txs
  ) {
  }
}
