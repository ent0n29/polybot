package com.polybot.hft.polymarket.strategy;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.polymarket.discovery.DiscoveredMarket;
import com.polybot.hft.polymarket.discovery.PolymarketMarketDiscoveryService;
import com.polybot.hft.polymarket.strategy.events.HouseEdgeDiscoverySelectedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class HouseEdgeMarketDiscoveryRunner {

  private static final Pattern FIFTEEN_MIN_PATTERN = Pattern.compile("(?i)\\b15\\s*(m|min(?:ute)?s?)\\b");

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketMarketDiscoveryService discoveryService;
  private final @NonNull HouseEdgeEngine engine;
  private final @NonNull HftEventPublisher events;

  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "house-edge-discovery");
    t.setDaemon(true);
    return t;
  });

  private final AtomicLong lastRefreshEpochMillis = new AtomicLong(0);
  private final AtomicInteger lastSelectedMarkets = new AtomicInteger(0);
  private volatile long lastNoMarketsLogAtMillis = 0L;
  private volatile Set<String> lastMarketKeys = Set.of();

  private static List<DiscoveredMarket> filterByAssetQueries(List<DiscoveredMarket> markets, List<String> queries) {
    if (markets == null || markets.isEmpty() || queries == null || queries.isEmpty()) {
      return markets == null ? List.of() : markets;
    }
    Set<String> needles = new HashSet<>();
    for (String q : queries) {
      if (q == null || q.isBlank()) {
        continue;
      }
      String norm = q.trim().toLowerCase(Locale.ROOT);
      needles.add(norm);
      switch (norm) {
        case "bitcoin", "btc" -> needles.add("btc");
        case "ethereum", "eth" -> needles.add("eth");
        case "solana", "sol" -> needles.add("sol");
        case "xrp", "ripple" -> {
          needles.add("xrp");
          needles.add("ripple");
        }
        default -> {
        }
      }
    }

    List<DiscoveredMarket> out = new ArrayList<>();
    for (DiscoveredMarket m : markets) {
      if (m == null) {
        continue;
      }
      String question = m.question() == null ? "" : m.question().toLowerCase(Locale.ROOT);
      String slug = m.slug() == null ? "" : m.slug().toLowerCase(Locale.ROOT);
      for (String n : needles) {
        if (n.isEmpty()) {
          continue;
        }
        if (question.contains(n) || slug.contains(n)) {
          out.add(m);
          break;
        }
      }
    }
    return out;
  }

  private static String marketKey(String yesTokenId, String noTokenId) {
    String yes = yesTokenId == null ? "" : yesTokenId.trim();
    String no = noTokenId == null ? "" : noTokenId.trim();
    return yes + ":" + no;
  }

  private static String safeName(String question) {
    if (question == null) {
      return null;
    }
    String q = question.trim();
    if (q.isEmpty()) {
      return null;
    }
    return q.length() <= 120 ? q : q.substring(0, 117) + "...";
  }

  @PostConstruct
  void startIfEnabled() {
    HftProperties.HouseEdge cfg = properties.strategy().houseEdge();
    HftProperties.HouseEdgeDiscovery discovery = cfg.discovery();
    if (!cfg.enabled() || discovery == null || !discovery.enabled()) {
      return;
    }
    long periodSeconds = Math.max(5, discovery.refreshSeconds());
    executor.scheduleAtFixedRate(this::refreshOnceSafe, 0, periodSeconds, TimeUnit.SECONDS);
    log.info("house-edge discovery started (queries={}, periodSeconds={}, maxMarkets={})",
        discovery.queries().size(),
        periodSeconds,
        discovery.maxMarkets());
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private void refreshOnceSafe() {
    try {
      refreshOnce();
    } catch (Exception e) {
      log.debug("house-edge discovery refresh error: {}", e.toString());
    }
  }

  private void refreshOnce() {
    lastRefreshEpochMillis.set(System.currentTimeMillis());
    HftProperties.HouseEdge cfg = properties.strategy().houseEdge();
    HftProperties.HouseEdgeDiscovery discovery = cfg.discovery();
    if (!cfg.enabled() || discovery == null || !discovery.enabled()) {
      return;
    }

    boolean require15m = discovery.require15m() == null || discovery.require15m();

    List<DiscoveredMarket> candidates = new ArrayList<>();
    int gammaCandidateCount = 0;
    int gammaAfterAssetFilterCount = 0;
    int clobCandidateCount = 0;
    if (require15m) {
      List<DiscoveredMarket> upOrDown = discoveryService.searchGammaUpOrDown15mEndingSoon(discovery.queries());
      gammaCandidateCount = upOrDown.size();
      List<DiscoveredMarket> gammaFiltered = filterByAssetQueries(upOrDown, discovery.queries());
      gammaAfterAssetFilterCount = gammaFiltered.size();
      candidates.addAll(gammaFiltered);
    } else {
      for (String q : discovery.queries()) {
        candidates.addAll(discoveryService.searchGamma(q));
      }
    }
    if (candidates.isEmpty()) {
      for (String q : discovery.queries()) {
        List<DiscoveredMarket> scanned = discoveryService.scanClobByQuestionContains(q);
        clobCandidateCount += scanned.size();
        candidates.addAll(scanned);
      }
    }

    BigDecimal minVolume = discovery.minVolume() == null ? BigDecimal.ZERO : discovery.minVolume();

    Map<String, DiscoveredMarket> bestByKey = new HashMap<>();
    for (DiscoveredMarket m : candidates) {
      if (m == null || m.yesTokenId() == null || m.noTokenId() == null) {
        continue;
      }
      String question = m.question() == null ? "" : m.question().trim();
      if (question.isBlank()) {
        continue;
      }
      boolean fromTagged15mSearch = m.source() != null && m.source().contains("15m");
      if (require15m && !fromTagged15mSearch && !FIFTEEN_MIN_PATTERN.matcher(question).find()) {
        continue;
      }
      BigDecimal volume = m.volume() == null ? BigDecimal.ZERO : m.volume();
      if (minVolume.compareTo(BigDecimal.ZERO) > 0 && volume.compareTo(minVolume) < 0) {
        continue;
      }

      String key = marketKey(m.yesTokenId(), m.noTokenId());
      DiscoveredMarket prev = bestByKey.get(key);
      if (prev == null) {
        bestByKey.put(key, m);
      } else {
        BigDecimal prevVol = prev.volume() == null ? BigDecimal.ZERO : prev.volume();
        if (volume.compareTo(prevVol) > 0) {
          bestByKey.put(key, m);
        }
      }
    }

    List<DiscoveredMarket> filtered = new ArrayList<>(bestByKey.values());
    if (filtered.isEmpty()) {
      maybeLogNoMarkets(discovery, gammaCandidateCount, gammaAfterAssetFilterCount, clobCandidateCount);
      return;
    }

    if (require15m) {
      filtered.sort((a, b) -> {
        Long ea = a.endEpochMillis();
        Long eb = b.endEpochMillis();
        if (ea != null && eb != null) {
          int cmp = ea.compareTo(eb);
          if (cmp != 0) {
            return cmp;
          }
        } else if (ea != null) {
          return -1;
        } else if (eb != null) {
          return 1;
        }
        BigDecimal va = a.volume() == null ? BigDecimal.ZERO : a.volume();
        BigDecimal vb = b.volume() == null ? BigDecimal.ZERO : b.volume();
        return vb.compareTo(va);
      });
    } else {
      filtered.sort((a, b) -> {
        BigDecimal va = a.volume() == null ? BigDecimal.ZERO : a.volume();
        BigDecimal vb = b.volume() == null ? BigDecimal.ZERO : b.volume();
        return vb.compareTo(va);
      });
    }

    int max = Math.max(1, discovery.maxMarkets());
    List<DiscoveredMarket> selected = filtered.stream().limit(max).toList();
    List<HftProperties.HouseEdgeMarket> next = selected.stream()
        .map(m -> new HftProperties.HouseEdgeMarket(safeName(m.question()), m.yesTokenId(), m.noTokenId()))
        .toList();

    Set<String> nextKeys = new HashSet<>();
    for (HftProperties.HouseEdgeMarket m : next) {
      nextKeys.add(marketKey(m.yesTokenId(), m.noTokenId()));
    }

    if (nextKeys.equals(lastMarketKeys)) {
      return;
    }
    lastMarketKeys = Set.copyOf(nextKeys);

    List<String> labels = next.stream()
        .map(m -> m.name() == null ? null : m.name().trim())
        .filter(s -> s != null && !s.isBlank())
        .limit(3)
        .toList();
    log.info("house-edge discovered markets selected={} markets={}", next.size(), labels);
    lastSelectedMarkets.set(next.size());
    if (events.isEnabled()) {
      List<HouseEdgeDiscoverySelectedEvent.SelectedMarket> markets = selected.stream()
          .map(m -> new HouseEdgeDiscoverySelectedEvent.SelectedMarket(
              safeName(m.question()),
              m.yesTokenId(),
              m.noTokenId(),
              m.endEpochMillis(),
              m.volume(),
              m.source()
          ))
          .toList();
      events.publish(HftEventTypes.STRATEGY_HOUSE_EDGE_DISCOVERY_SELECTED, "house-edge", new HouseEdgeDiscoverySelectedEvent(
          List.copyOf(discovery.queries()),
          require15m,
          minVolume,
          max,
          next.size(),
          gammaCandidateCount,
          gammaAfterAssetFilterCount,
          clobCandidateCount,
          markets
      ));
    }
    engine.setMarkets(next);
  }

  private void maybeLogNoMarkets(
      HftProperties.HouseEdgeDiscovery discovery,
      int gammaCandidateCount,
      int gammaAfterAssetFilterCount,
      int clobCandidateCount
  ) {
    long now = System.currentTimeMillis();
    long last = lastNoMarketsLogAtMillis;
    if (now - last < 60_000L) {
      return;
    }
    lastNoMarketsLogAtMillis = now;
    log.info("house-edge discovery found no markets (queries={}, require15m={}, minVolume={}, maxMarkets={}, gammaCandidates={}, gammaAfterAssetFilter={}, clobCandidates={})",
        discovery.queries(),
        discovery.require15m(),
        discovery.minVolume(),
        discovery.maxMarkets(),
        gammaCandidateCount,
        gammaAfterAssetFilterCount,
        clobCandidateCount);
  }

  public long lastRefreshEpochMillis() {
    return lastRefreshEpochMillis.get();
  }

  public int lastSelectedMarkets() {
    return lastSelectedMarkets.get();
  }
}
