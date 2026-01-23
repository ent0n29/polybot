#!/usr/bin/env python3
"""
Paper Trading Dashboard - Monitor your target user clone in real-time.

Usage:
    ./.venv/bin/python research/paper_trading_dashboard.py
    ./.venv/bin/python research/paper_trading_dashboard.py --watch  # Auto-refresh every 30s
"""

import os
import argparse
import time
from datetime import datetime, timezone
import clickhouse_connect

def get_client():
    return clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

def print_header(title: str):
    print(f"\n{'='*70}")
    print(f" {title}")
    print(f"{'='*70}")

def print_section(title: str):
    print(f"\n--- {title} ---")

def run_dashboard():
    client = get_client()
    now = datetime.now(timezone.utc)

    print_header(f"PAPER TRADING DASHBOARD - {now.strftime('%Y-%m-%d %H:%M:%S')} UTC")

    # 1. Strategy Order Activity
    print_section("Strategy Orders (Last 10 min)")
    orders = client.query("""
        SELECT
            count() as total,
            countIf(action = 'PLACE') as places,
            countIf(action = 'CANCEL') as cancels,
            countIf(success = 1) as successful,
            uniqExact(market_slug) as markets
        FROM polybot.strategy_gabagool_orders
        WHERE ts > now() - INTERVAL 10 MINUTE
    """).result_rows
    if orders and orders[0][0] > 0:
        total, places, cancels, successful, markets = orders[0]
        print(f"  Total: {total} | Places: {places} | Cancels: {cancels} | Success: {successful} | Markets: {markets}")
    else:
        print("  No orders in last 10 minutes")

    # 2. Executor Fill Summary
    print_section("Paper Exchange Fills (All Time)")
    fills = client.query("""
        SELECT
            exchange_status,
            count() as orders,
            round(coalesce(sum(matched_size), 0), 2) as filled_shares,
            round(coalesce(sum(matched_size * requested_price), 0), 2) as filled_notional
        FROM polybot.executor_order_status
        GROUP BY exchange_status
        ORDER BY orders DESC
    """).result_rows
    print(f"  {'Status':<20} {'Orders':>8} {'Shares':>12} {'Notional':>12}")
    print(f"  {'-'*52}")
    for row in fills:
        status, orders, shares, notional = row
        print(f"  {status:<20} {orders:>8} {shares:>12.2f} ${notional:>11.2f}")

    # 3. Recent Fills
    print_section("Recent Fills (Last 10)")
    recent = client.query("""
        SELECT
            ts,
            substring(order_id, 1, 20) as order_id,
            side,
            requested_price,
            matched_size,
            exchange_status
        FROM polybot.executor_order_status
        WHERE matched_size > 0
        ORDER BY ts DESC
        LIMIT 10
    """).result_rows
    print(f"  {'Time':<12} {'Order ID':<22} {'Side':<5} {'Price':>7} {'Size':>8} {'Status':<18}")
    print(f"  {'-'*75}")
    for row in recent:
        ts, oid, side, price, size, status = row
        time_str = ts.strftime('%H:%M:%S') if hasattr(ts, 'strftime') else str(ts)[-12:-4]
        print(f"  {time_str:<12} {oid:<22} {side:<5} {price:>7.2f} {size:>8.2f} {status:<18}")

    # 4. Per-Market Performance (simulated)
    print_section("Simulated Performance by Market Type")
    perf = client.query("""
        SELECT
            multiIf(
                market_slug LIKE '%btc-updown-15m%', '15min-BTC',
                market_slug LIKE '%eth-updown-15m%', '15min-ETH',
                market_slug LIKE '%bitcoin-up-or-down%', '1hour-BTC',
                market_slug LIKE '%ethereum-up-or-down%', '1hour-ETH',
                'Other'
            ) as series,
            count() as orders,
            round(sum(size), 2) as total_shares,
            round(sum(price * size), 2) as total_notional,
            round(avg(price), 4) as avg_price
        FROM polybot.user_trades
        WHERE ts > now() - INTERVAL 1 HOUR
          AND transaction_hash = ''
        GROUP BY series
        ORDER BY orders DESC
    """).result_rows
    if perf:
        print(f"  {'Series':<12} {'Orders':>8} {'Shares':>10} {'Notional':>12} {'Avg Price':>10}")
        print(f"  {'-'*55}")
        for row in perf:
            series, orders, shares, notional, avg_price = row
            print(f"  {series:<12} {orders:>8} {shares:>10.2f} ${notional:>11.2f} {avg_price:>10.4f}")
    else:
        print("  No simulated trades in last hour")

    # 5. Inventory Balance (from executor positions)
    print_section("Current Paper Positions")
    positions = client.query("""
        SELECT
            token_id,
            round(sum(matched_size), 2) as shares,
            round(sum(matched_size * requested_price), 2) as cost_basis
        FROM polybot.executor_order_status
        WHERE exchange_status IN ('FILLED', 'PARTIALLY_FILLED')
          AND side = 'BUY'
        GROUP BY token_id
        HAVING shares > 0
        ORDER BY shares DESC
        LIMIT 10
    """).result_rows
    if positions:
        print(f"  {'Token (last 8)':<12} {'Shares':>10} {'Cost Basis':>12}")
        print(f"  {'-'*36}")
        for row in positions:
            token_id, shares, cost = row
            token_short = f"...{token_id[-8:]}" if len(token_id) > 8 else token_id
            print(f"  {token_short:<12} {shares:>10.2f} ${cost:>11.2f}")
    else:
        print("  No positions yet")

    # 6. Data Quality (WS coverage)
    print_section("WS TOB Coverage (Last 10 min)")
    target_user = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
    ws = client.query(f"""
        SELECT
            count() as trades,
            countIf(ws_mid > 0) as with_ws,
            round(100.0 * countIf(ws_mid > 0) / count(), 1) as coverage_pct,
            round(avgIf(ws_tob_lag_millis, ws_mid > 0), 0) as avg_lag_ms
        FROM polybot.user_trade_enriched_v4
        WHERE username = '{target_user}'
          AND ts > now() - INTERVAL 10 MINUTE
    """).result_rows
    if ws and ws[0][0] > 0:
        trades, with_ws, coverage, lag = ws[0]
        print(f"  Trades: {trades} | With WS: {with_ws} | Coverage: {coverage}% | Avg Lag: {lag}ms")
    else:
        print("  No trades in last 10 minutes")

    # 7. target user Activity (for comparison)
    print_section("target user Activity (Last 10 min)")
    gab = client.query(f"""
        SELECT
            count() as trades,
            round(sum(price * size), 2) as notional,
            round(sumIf(realized_pnl, outcome != ''), 2) as realized_pnl
        FROM polybot.user_trade_enriched_v4
        WHERE username = '{target_user}'
          AND ts > now() - INTERVAL 10 MINUTE
    """).result_rows
    if gab and gab[0][0] > 0:
        trades, notional, pnl = gab[0]
        print(f"  Trades: {trades} | Notional: ${notional:.2f} | Realized PnL: ${pnl:.2f}")
    else:
        print("  No target user trades in last 10 minutes")

    # 8. Service Health
    print_section("Service Health")
    try:
        import requests
        services = [
            ("Executor", "http://localhost:8080/actuator/health"),
            ("Strategy", "http://localhost:8081/actuator/health"),
            ("Ingestor", "http://localhost:8083/api/ingestor/status"),
        ]
        for name, url in services:
            try:
                r = requests.get(url, timeout=2)
                if r.status_code == 200:
                    print(f"  {name}: UP")
                else:
                    print(f"  {name}: DOWN (status {r.status_code})")
            except:
                print(f"  {name}: DOWN (not responding)")
    except ImportError:
        print("  (install 'requests' for health checks)")

    print(f"\n{'='*70}\n")

def main():
    parser = argparse.ArgumentParser(description="Paper Trading Dashboard")
    parser.add_argument("--watch", action="store_true", help="Auto-refresh every 30 seconds")
    parser.add_argument("--interval", type=int, default=30, help="Refresh interval in seconds")
    args = parser.parse_args()

    if args.watch:
        print("Starting dashboard in watch mode (Ctrl+C to stop)...")
        while True:
            try:
                # Clear screen
                print("\033[2J\033[H", end="")
                run_dashboard()
                time.sleep(args.interval)
            except KeyboardInterrupt:
                print("\nStopping dashboard...")
                break
    else:
        run_dashboard()

if __name__ == "__main__":
    main()
