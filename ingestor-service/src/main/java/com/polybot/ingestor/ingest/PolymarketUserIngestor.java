package com.polybot.ingestor.ingest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.ingestor.config.IngestorProperties;
import com.polybot.ingestor.polymarket.PolymarketDataApiClient;
import com.polybot.ingestor.polymarket.PolymarketProfileResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketUserIngestor {

  private static final Pattern ETH_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
  private static final long POSITIONS_SNAPSHOT_MIN_INTERVAL_MILLIS = 60_000L;
  private static final int DEFAULT_SEEN_KEYS_CAPACITY = 25_000;
  private static final int DATA_API_MAX_LIMIT = 500;
  private static final int DATA_API_MAX_OFFSET = 1_000;

  private final @NonNull IngestorProperties properties;
  private final @NonNull PolymarketProfileResolver profileResolver;
  private final @NonNull PolymarketDataApiClient dataApi;
  private final @NonNull PolymarketMarketContextIngestor marketContext;
  private final @NonNull HftEventPublisher events;
  private final @NonNull Clock clock;

  private final AtomicBoolean initOnce = new AtomicBoolean(false);
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean pollingNow = new AtomicBoolean(false);

  private final EvictingKeySet seenTradeKeys = new EvictingKeySet(DEFAULT_SEEN_KEYS_CAPACITY);

  private final AtomicLong polls = new AtomicLong(0);
  private final AtomicLong publishedTrades = new AtomicLong(0);
  private final AtomicLong publishedPositionSnapshots = new AtomicLong(0);
  private final AtomicLong failures = new AtomicLong(0);

  private volatile Target target;
  private volatile long lastPollAtMillis;
  private volatile long lastPositionsSnapshotAtMillis;

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!initOnce.compareAndSet(false, true)) {
      return;
    }
    if (!properties.polymarket().hasIdentity()) {
      log.warn("ingestor missing target identity: set ingestor.polymarket.username or ingestor.polymarket.proxy-address");
      started.set(true);
      return;
    }

    try {
      resolveTargetIfNeeded();
    } catch (Exception e) {
      failures.incrementAndGet();
      log.warn("ingestor resolve target failed error={}", e.toString());
    }

    if (properties.polling().backfillOnStart() && target != null) {
      try {
        backfillTrades(target);
      } catch (Exception e) {
        failures.incrementAndGet();
        log.warn("ingestor backfill failed error={}", e.toString());
      }
    }

    started.set(true);
  }

  @Scheduled(initialDelayString = "1000", fixedDelayString = "${ingestor.polling.poll-interval-seconds:15}000")
  public void poll() {
    if (!properties.polling().enabled()) {
      return;
    }
    if (!started.get()) {
      return;
    }
    if (!pollingNow.compareAndSet(false, true)) {
      return;
    }

    try {
      polls.incrementAndGet();
      lastPollAtMillis = Instant.now(clock).toEpochMilli();

      resolveTargetIfNeeded();
      Target t = target;
      if (t == null) {
        return;
      }

      int newTrades = pollLatestTrades(t);

      long nowMillis = lastPollAtMillis;
      boolean duePositions = nowMillis - lastPositionsSnapshotAtMillis >= POSITIONS_SNAPSHOT_MIN_INTERVAL_MILLIS;
      if (newTrades > 0 || duePositions) {
        pollPositionsSnapshot(t);
      }
    } catch (Exception e) {
      failures.incrementAndGet();
      log.warn("ingestor poll failed error={}", e.toString());
    } finally {
      pollingNow.set(false);
    }
  }

  private void resolveTargetIfNeeded() {
    if (target != null) {
      return;
    }

    String proxyAddress = normalizeAddress(properties.polymarket().proxyAddress());
    if (proxyAddress != null) {
      target = new Target(properties.polymarket().username(), null, proxyAddress, null, Instant.now(clock));
      publishIdentity(target);
      log.info("ingestor target resolved via config username={} proxyAddress={}", safe(properties.polymarket().username()), suffix(proxyAddress));
      return;
    }

    String username = properties.polymarket().username();
    if (username == null || username.isBlank()) {
      return;
    }

    profileResolver.resolve(username).ifPresent(identity -> {
      String resolvedProxy = normalizeAddress(identity.proxyAddress());
      String resolvedPrimary = normalizeAddress(identity.primaryAddress());
      String resolvedBase = normalizeAddress(identity.baseAddress());
      String best = firstNonNull(resolvedProxy, resolvedPrimary, resolvedBase);
      if (best == null) {
        return;
      }
      target = new Target(identity.username(), resolvedBase, resolvedProxy != null ? resolvedProxy : best, resolvedPrimary, Instant.now(clock));
      publishIdentity(target);
      log.info("ingestor target resolved via profile username={} proxyAddress={} baseAddress={}", identity.username(), suffix(target.proxyAddress), suffix(target.baseAddress));
    });
  }

  private void publishIdentity(Target t) {
    if (!events.isEnabled()) {
      return;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("username", t.username);
    data.put("proxyAddress", t.proxyAddress);
    data.put("baseAddress", t.baseAddress);
    data.put("primaryAddress", t.primaryAddress);
    data.put("resolvedAt", t.resolvedAt);
    events.publish(Instant.now(clock), "ingestor.polymarket.user.identity", t.proxyAddress, data);
  }

  private void backfillTrades(Target t) {
    Integer maxPages = properties.polling().backfillMaxPages();
    int pageSize = Math.max(1, Math.min(DATA_API_MAX_LIMIT, properties.polling().pageSize()));
    long delayMillis = properties.polling().requestDelayMillis();

    log.info("ingestor backfill starting username={} proxyAddress={} pageSize={} maxPages={}", safe(t.username), suffix(t.proxyAddress), pageSize, maxPages);

    int offset = 0;
    int page = 0;
    boolean fetchedTail = false;
    String lastSignature = null;
    while (true) {
      if (maxPages != null && page >= maxPages) {
        break;
      }

      int requestOffset = offset;
      int requestLimit = pageSize;
      if (requestOffset >= DATA_API_MAX_OFFSET) {
        if (fetchedTail) {
          break;
        }
        fetchedTail = true;
        requestOffset = DATA_API_MAX_OFFSET;
        requestLimit = DATA_API_MAX_LIMIT;
      }

      ArrayNode trades = dataApi.getTrades(t.proxyAddress, requestLimit, requestOffset);
      if (trades.isEmpty()) {
        break;
      }

      int published = publishTrades(t, trades);
      String signature = pageSignature(trades);
      if (signature != null && signature.equals(lastSignature)) {
        log.info("ingestor backfill reached stable tail offset={} limit={} fetched={} published={}", requestOffset, requestLimit, trades.size(), published);
        break;
      }
      lastSignature = signature;

      if (page % 10 == 0) {
        log.info("ingestor backfill progress page={} offset={} limit={} fetched={} published={}", page, requestOffset, requestLimit, trades.size(), published);
      }

      if (fetchedTail) {
        break;
      }

      offset += trades.size();
      page++;
      sleep(delayMillis);
    }

    log.info("ingestor backfill done pages={} publishedTrades={}", page, publishedTrades.get());
  }

  private int pollLatestTrades(Target t) {
    int pageSize = properties.polling().pageSize();
    ArrayNode trades = dataApi.getTrades(t.proxyAddress, pageSize, 0);
    if (trades.isEmpty()) {
      return 0;
    }

    int published = publishTrades(t, trades);
    if (published > 0) {
      log.info("ingestor trades poll username={} proxyAddress={} fetched={} published={}", safe(t.username), suffix(t.proxyAddress), trades.size(), published);
    }
    return published;
  }

  private void pollPositionsSnapshot(Target t) {
    if (!events.isEnabled()) {
      return;
    }
    int pageSize = Math.max(1, properties.polling().pageSize());
    long delayMillis = properties.polling().requestDelayMillis();
    Integer maxPages = properties.polling().backfillMaxPages();
    int hardCapPages = 50;

    ArrayNode positions = dataApi.getPositions(t.proxyAddress, pageSize, 0);
    ArrayNode all = positions;
    int offset = positions.size();
    int page = 1;
    while (!positions.isEmpty()) {
      if (maxPages != null && page >= maxPages) {
        break;
      }
      if (page >= hardCapPages) {
        break;
      }
      sleep(delayMillis);
      positions = dataApi.getPositions(t.proxyAddress, pageSize, offset);
      if (positions.isEmpty()) {
        break;
      }
      all.addAll(positions);
      offset += positions.size();
      page++;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("username", t.username);
    data.put("proxyAddress", t.proxyAddress);
    data.put("positions", all);

    events.publish(Instant.now(clock), "polymarket.user.positions.snapshot", t.proxyAddress, data);
    publishedPositionSnapshots.incrementAndGet();
    lastPositionsSnapshotAtMillis = Instant.now(clock).toEpochMilli();
    log.info("ingestor positions snapshot username={} proxyAddress={} positions={}", safe(t.username), suffix(t.proxyAddress), all.size());
  }

  private int publishTrades(Target t, ArrayNode trades) {
    if (!events.isEnabled()) {
      return 0;
    }
    int published = 0;
    for (int i = trades.size() - 1; i >= 0; i--) {
      var trade = trades.get(i);
      if (trade == null || trade.isNull()) {
        continue;
      }

      long tsSeconds = trade.path("timestamp").asLong(0);
      Instant ts = tsSeconds > 0 ? Instant.ofEpochSecond(tsSeconds) : Instant.now(clock);

      String tx = trade.path("transactionHash").asText(null);
      String asset = trade.path("asset").asText("");
      String side = trade.path("side").asText("");
      String eventKey = buildTradeEventKey(t.proxyAddress, tx, asset, side, tsSeconds);

      if (!seenTradeKeys.add(eventKey)) {
        continue;
      }

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("username", t.username);
      data.put("proxyAddress", t.proxyAddress);
      data.put("trade", trade);

      events.publish(ts, "polymarket.user.trade", eventKey, data);
      publishedTrades.incrementAndGet();
      published++;

      marketContext.onUserTrade(t.username, t.proxyAddress, eventKey, ts, trade);
    }
    return published;
  }

  private static String pageSignature(ArrayNode trades) {
    if (trades == null || trades.isEmpty()) {
      return null;
    }
    String firstTx = trades.get(0).path("transactionHash").asText("");
    String lastTx = trades.get(trades.size() - 1).path("transactionHash").asText("");
    long firstTs = trades.get(0).path("timestamp").asLong(0);
    long lastTs = trades.get(trades.size() - 1).path("timestamp").asLong(0);
    return "%d|%s|%d|%s|%d".formatted(trades.size(), firstTx, firstTs, lastTx, lastTs);
  }

  private static String buildTradeEventKey(String proxyAddress, String transactionHash, String asset, String side, long tsSeconds) {
    String tx = transactionHash == null ? "" : transactionHash.trim();
    String a = asset == null ? "" : asset.trim();
    String s = side == null ? "" : side.trim();
    if (!tx.isEmpty()) {
      return "%s:%s:%s".formatted(tx, a, s);
    }
    return "%s:%d:%s:%s".formatted(proxyAddress, tsSeconds, a, s);
  }

  private static String normalizeAddress(String address) {
    if (address == null) {
      return null;
    }
    String v = address.trim();
    if (v.isEmpty()) {
      return null;
    }
    return ETH_ADDRESS.matcher(v).matches() ? v : null;
  }

  private static String firstNonNull(String... values) {
    if (values == null) {
      return null;
    }
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String safe(String v) {
    return v == null || v.isBlank() ? "?" : v.trim();
  }

  private static String suffix(String address) {
    if (address == null || address.length() < 10) {
      return String.valueOf(address);
    }
    return "..." + address.substring(address.length() - 6);
  }

  private record Target(
      String username,
      String baseAddress,
      String proxyAddress,
      String primaryAddress,
      Instant resolvedAt
  ) {
  }

  public long polls() {
    return polls.get();
  }

  public long publishedTrades() {
    return publishedTrades.get();
  }

  public long publishedPositionSnapshots() {
    return publishedPositionSnapshots.get();
  }

  public long failures() {
    return failures.get();
  }

  public long lastPollAtMillis() {
    return lastPollAtMillis;
  }

  public long lastPositionsSnapshotAtMillis() {
    return lastPositionsSnapshotAtMillis;
  }

  public TargetStatus target() {
    Target t = target;
    if (t == null) {
      return null;
    }
    return new TargetStatus(t.username, t.baseAddress, t.proxyAddress, t.primaryAddress, t.resolvedAt);
  }

  public record TargetStatus(
      String username,
      String baseAddress,
      String proxyAddress,
      String primaryAddress,
      Instant resolvedAt
  ) {
  }
}
