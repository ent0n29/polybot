# MuseumOfBees (@MuseumOfBees) — Bot Strategy Reverse Engineering

**Wallet:** `0x61276aba49117fd9299707d5d573652949d5c977`
**Analysis Date:** 2026-02-27
**Data:** 4,000 trades across ~13 hours (API max 3000 offset limit)

---

## Executive Summary

MuseumOfBees runs a **high-frequency complete-set arbitrage bot** on Polymarket's 5-minute BTC/ETH Up/Down binary markets. The bot buys **both outcomes** (Up + Down) for each market, assembling **complete sets** that pay $1.00 at settlement regardless of outcome. When the combined cost of buying both sides is less than $1.00, the difference is pure profit.

**Key stats:**
- 4,000 trades in ~13 hours (~300 trades/hour)
- $76K total volume
- 307 unique markets
- 100% BUY side (never sells — purely accumulates both outcomes)
- ~$248 average volume per market
- Runs 24/7 across every consecutive 5-minute window

---

## 1. Strategy: Complete-Set Arbitrage

### How It Works

Polymarket's Up/Down binaries have two outcomes (Up and Down) that sum to $1.00 at settlement. If you can buy 1 share of "Up" at $0.45 and 1 share of "Down" at $0.50, your total cost is $0.95. At settlement, one pays $1.00 and the other $0.00 — but you hold both, so you collect $1.00 no matter what. Net profit: $0.05 per share.

### MuseumOfBees' Implementation

| Metric | Value |
|---|---|
| Complete sets assembled | 289 out of 307 markets (94%) |
| Average set cost | $0.9566 |
| Best set cost | $0.4975 (50.25% edge!) |
| Profitable sets (<$1.00) | 185/289 (64%) |
| Unprofitable sets (>=$1.00) | 104/289 (36%) |
| Total estimated edge | $4,824 |

### Set Cost Distribution

Most sets cost between $0.85–$1.00, with a healthy tail of high-edge sets in the $0.65–$0.85 range. The bot is opportunistic — it doesn't require a minimum edge to enter, but captures the largest profits when spreads are wide.

---

## 2. Market Selection

### What They Trade

| Market Type | Trade Count | % of Total |
|---|---|---|
| BTC Up/Down 5-minute | 2,287 | 57.2% |
| ETH Up/Down 5-minute | 1,713 | 42.8% |

**Only 5-minute binaries.** No 15-minute, no 1-hour. This is a deliberate choice — shorter duration = faster capital turnover = more opportunities per hour.

### Participation Rate

- **155 unique 5-minute windows** traded out of ~156 available (nearly 100%)
- **152 windows had both BTC + ETH** traded simultaneously
- **Max consecutive windows: 111** (9.25 hours non-stop)
- Gaps are extremely rare (only 2 skipped windows in entire dataset)

**Conclusion:** The bot trades **every single 5-minute window**, for both BTC and ETH, 24/7.

---

## 3. Entry Timing

### When Does the Bot Enter?

| Metric | Value |
|---|---|
| First trade (mean offset from market open) | 29.0 seconds |
| First trade (median) | 16.0 seconds |
| First trade (min) | 6 seconds |
| 58% of first entries | Within 20 seconds of market opening |

The bot enters **fast** — within 6–20 seconds of the market opening. It doesn't wait for the market to develop. This is critical: early entry means access to the widest spreads before other market makers compress them.

### Entry Spread Across Market Lifetime

Trades are distributed across the entire 5-minute window, with a slight bias toward early entry:

| Time into market | % of trades |
|---|---|
| 0–60 seconds | 28.2% |
| 60–120 seconds | 22.6% |
| 120–180 seconds | 20.4% |
| 180–240 seconds | 22.7% |
| 240–300 seconds | 6.1% |

The bot front-loads but continues buying throughout. Notably, activity drops off sharply in the last minute (240–300s), suggesting the bot has a cutoff ~60 seconds before market end.

---

## 4. Order Flow Pattern

### Which Side First?

| First outcome bought | Count |
|---|---|
| Up first | 175 markets (57%) |
| Down first | 132 markets (43%) |

Slight preference for buying "Up" first, but close to 50/50. The bot likely just hits whichever side has better prices first.

### Trade Burst Pattern (Inter-trade gaps)

| Gap | % of trades |
|---|---|
| 0 seconds (same tick) | 33.1% |
| 2 seconds | 15.8% |
| 4 seconds | 6.4% |
| 6 seconds | 5.3% |
| 8 seconds | 4.3% |

**One-third of all trades happen within the same second.** This is classic order-book sweeping — the bot sends market/aggressive limit orders that fill across multiple price levels simultaneously. The 2-second gap is likely the Polymarket CLOB's processing time between orders.

### Sweep Behavior

- **691 sweep events** (multiple fills in the same second)
- Average fills per sweep: **2.8**
- Max fills per sweep: **12** (eating through many levels at once)
- 75% of sweeps have zero spread within same outcome → hitting resting limit orders

### Complete-Set Assembly Time

| Metric | Value |
|---|---|
| Mean time between first Up and first Down fill | 40.2 seconds |
| Median | 26.0 seconds |
| Same second (simultaneous) | 5/289 |
| Within 10 seconds | 82/289 (28%) |
| Within 30 seconds | 160/289 (55%) |

The bot assembles both legs quickly — usually within 30 seconds. This minimizes directional risk exposure.

---

## 5. Sizing

### Order Size Distribution

| Size Range | % of Trades |
|---|---|
| 0–5 shares | 4.6% |
| 5–10 shares | 13.7% |
| 10–20 shares | 19.9% |
| **20–50 shares** | **39.2% (dominant)** |
| 50–100 shares | 10.7% |
| 100–200 shares | 9.6% |
| 200–500 shares | 2.5% |

**Core position size: 20 shares.** The most common exact size is 20.00 (512 trades / 12.8%). This suggests the bot uses a fixed base order size of **$20** (in shares), which at ~$0.50 per share = ~$10 USDC per order.

### Size by Timing

Size is **relatively constant** throughout the market window (~$37–47 average per trade), with slightly larger orders at entry (0–30s: $47.72 avg) and near the end (240–270s: $46.21 avg), suggesting the bot pushes more aggressively at open (grab best prices) and does cleanup/top-up near close.

---

## 6. Price Behavior

### Average Entry Price: **$0.4916**

Prices cluster near $0.45–0.55 (where Up/Down are most uncertain and spreads widest). The bot doesn't chase extreme prices ($0.01 or $0.99) — those are low-edge, highly efficient markets.

### Price vs Timing

| Time bucket | Avg price |
|---|---|
| 0–30s | $0.4848 |
| 30–60s | $0.4910 |
| 60–90s | $0.4757 |
| 90–120s | $0.4960 |

Prices stay remarkably stable around $0.49 throughout, confirming the bot doesn't change strategy based on time — it simply sweeps available liquidity at any price that creates a profitable complete set.

---

## 7. The Bot's Algorithm (Reconstructed)

```
EVERY 5 MINUTES (on new BTC and ETH Up/Down market open):
  
  1. DISCOVER: Query for new BTC-UpDown-5m and ETH-UpDown-5m markets
  
  2. ENTER FAST: Within 6-20 seconds of market open
  
  3. FOR EACH MARKET (BTC and ETH simultaneously):
     
     a. CHECK BOTH ORDER BOOKS (Up and Down)
     
     b. IF best_ask(Up) + best_ask(Down) < $1.00:
        → BUY both sides aggressively
        → Use market orders / sweeps (fill at multiple price levels)
        → Base order size: ~20 shares ($10 USDC)
        → No strict minimum edge filter (quotes aggressively)
     
     c. REPEAT every ~2 seconds:
        → Alternate between buying Up and Down
        → Maintain rough balance (50/50 allocation)
        → Accept fills at varying prices (taker behavior)
     
     d. REBALANCE as needed:
        → If one side has more shares, prioritize the other
        → Top-up with larger orders (100-200 shares) when good prices appear
     
     e. CUTOFF: Stop buying ~60 seconds before market end
  
  4. AT SETTLEMENT:
     → All matched shares (min of Up, Down) redeem for $1.00 each
     → Unmatched excess is directional risk (win or lose)
     
  5. ROLL CAPITAL into next 5-minute window and repeat
```

### Key Parameters (Estimated)

| Parameter | Value |
|---|---|
| Base order size | 20 shares |
| Max single order | ~380 shares |
| Order frequency | Every 2 seconds |
| Entry delay after market open | 6–20 seconds |
| Cutoff before market end | ~60 seconds |
| Markets per window | 2 (BTC + ETH) |
| Target set cost | No strict minimum, but favors < $0.95 |
| Avg edge per set | $0.083 (8.3 cents/share) |
| Daily market participation | 24/7, every 5-min window |

---

## 8. Risk Profile

### Strengths
- **Market-neutral by design** — no directional risk on matched shares
- **High frequency** — captures edge 288 times per day (per asset)
- **Scalable** — can increase size as liquidity allows
- **Automated** — runs 24/7 without human intervention

### Weaknesses
- **Imbalance risk** — average imbalance of 131 shares per market means significant unmatched directional exposure
- **36% of sets cost > $1.00** — the bot sometimes overpays, likely when chasing liquidity
- **Counterparty risk** — dependent on Polymarket settlement mechanics
- **Speed dependency** — must be faster than other arbitrageurs
- **Capital efficiency** — ~$500/market tied up for 5 minutes

---

## 9. Data Files

| File | Description |
|---|---|
| `all_trades.json` | All 4,000 trades merged into a single file |
| `trades_page0.json` – `trades_page3.json` | Raw API response pages |
| `STRATEGY_REPORT.md` | This report |
