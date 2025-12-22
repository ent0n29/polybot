package com.polybot.hft.executor.settlement;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoSettlementScheduler {

  private final @NonNull ExecutorSettlementProperties settlementProperties;
  private final @NonNull PolymarketSettlementService settlementService;

  @Scheduled(fixedDelayString = "${executor.settlement.poll-interval-millis:30000}")
  public void tick() {
    if (!settlementProperties.enabled()) {
      log.debug("auto-settlement disabled");
      return;
    }
    try {
      var res = settlementService.runOnce(null);
      // Always log the result for debugging
      if (res.ok() && "no-actions".equals(res.status())) {
        log.debug("auto-settlement: no actions needed");
      } else if (res.ok()) {
        log.info("auto-settlement: status={} planned={} txs={}",
            res.status(),
            res.plannedActions() == null ? 0 : res.plannedActions().size(),
            res.txs() == null ? 0 : res.txs().size());
        // Log each action result
        if (res.txs() != null) {
          for (var tx : res.txs()) {
            if (tx.submitted()) {
              log.info("  {} - SUBMITTED", tx.action().summary());
            } else {
              log.error("  {} - FAILED: {}", tx.action().summary(), tx.error());
            }
          }
        }
      } else {
        log.warn("auto-settlement: status={} ok={} dryRun={} planned={} txs={}",
            res.status(), res.ok(), res.dryRun(),
            res.plannedActions() == null ? 0 : res.plannedActions().size(),
            res.txs() == null ? 0 : res.txs().size());
      }
    } catch (Exception e) {
      log.error("auto-settlement tick failed", e);
    }
  }
}

