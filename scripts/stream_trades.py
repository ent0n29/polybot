#!/usr/bin/env python3
"""
Stream live trades and P&L from ClickHouse.
Usage: python stream_trades.py [--interval 5] [--slug btc-updown-15m]
"""
import argparse
import time
from datetime import datetime, timedelta
import clickhouse_connect
from rich.console import Console
from rich.table import Table
from rich.live import Live

console = Console()

def get_client():
    return clickhouse_connect.get_client(
        host='localhost',
        port=8123,
        database='polymarket'
    )

def fetch_recent_trades(client, slug_filter=None, since_minutes=15):
    """Fetch trades from the last N minutes."""
    since = datetime.utcnow() - timedelta(minutes=since_minutes)
    
    query = """
    SELECT 
        timestamp,
        slug,
        side,
        outcome,
        price,
        size,
        maker_address,
        fee_rate_bps
    FROM trades
    WHERE timestamp > toDateTime64(%(since)s, 3)
    """
    if slug_filter:
        query += " AND slug LIKE %(slug)s"
    query += " ORDER BY timestamp DESC LIMIT 50"
    
    params = {'since': since.strftime('%Y-%m-%d %H:%M:%S')}
    if slug_filter:
        params['slug'] = f'%{slug_filter}%'
    
    return client.query(query, parameters=params)

def fetch_pnl_summary(client, slug_filter=None, since_minutes=60):
    """Fetch P&L summary for recent trades."""
    since = datetime.utcnow() - timedelta(minutes=since_minutes)
    
    query = """
    SELECT 
        slug,
        outcome,
        COUNT(*) as trade_count,
        SUM(size) as total_shares,
        AVG(price) as avg_price,
        SUM(size * price) as total_notional
    FROM trades
    WHERE timestamp > toDateTime64(%(since)s, 3)
    """
    if slug_filter:
        query += " AND slug LIKE %(slug)s"
    query += " GROUP BY slug, outcome ORDER BY slug, outcome"
    
    params = {'since': since.strftime('%Y-%m-%d %H:%M:%S')}
    if slug_filter:
        params['slug'] = f'%{slug_filter}%'
    
    return client.query(query, parameters=params)

def calculate_pnl(positions):
    """Calculate estimated P&L from positions assuming complete-set arbitrage."""
    pnl_by_market = {}
    for row in positions.result_rows:
        slug, outcome, count, shares, avg_price, notional = row
        if slug not in pnl_by_market:
            pnl_by_market[slug] = {'UP': {'shares': 0, 'cost': 0}, 'DOWN': {'shares': 0, 'cost': 0}}
        pnl_by_market[slug][outcome]['shares'] = float(shares or 0)
        pnl_by_market[slug][outcome]['cost'] = float(notional or 0)
    
    results = []
    for slug, data in pnl_by_market.items():
        up_shares = data['UP']['shares']
        down_shares = data['DOWN']['shares']
        up_cost = data['UP']['cost']
        down_cost = data['DOWN']['cost']
        
        paired = min(up_shares, down_shares)
        total_cost = up_cost + down_cost
        
        # Complete set value = paired shares * $1.00
        complete_set_value = paired
        edge = complete_set_value - total_cost if paired > 0 else 0
        
        imbalance = up_shares - down_shares
        
        results.append({
            'slug': slug,
            'up_shares': up_shares,
            'down_shares': down_shares,
            'paired': paired,
            'imbalance': imbalance,
            'total_cost': total_cost,
            'edge_pnl': edge
        })
    return results

def build_trades_table(trades):
    table = Table(title="Recent Trades", show_header=True)
    table.add_column("Time", style="cyan")
    table.add_column("Slug", style="green")
    table.add_column("Side", style="yellow")
    table.add_column("Outcome", style="magenta")
    table.add_column("Price", justify="right")
    table.add_column("Size", justify="right")
    
    for row in trades.result_rows[:20]:
        ts, slug, side, outcome, price, size, maker, fee = row
        ts_str = ts.strftime('%H:%M:%S') if hasattr(ts, 'strftime') else str(ts)
        short_slug = slug[-30:] if len(slug) > 30 else slug
        table.add_row(
            ts_str,
            short_slug,
            side or 'N/A',
            outcome or 'N/A',
            f"{float(price):.4f}" if price else 'N/A',
            f"{float(size):.2f}" if size else 'N/A'
        )
    return table

def build_pnl_table(pnl_results):
    table = Table(title="P&L Summary (Complete-Set)", show_header=True)
    table.add_column("Market", style="green")
    table.add_column("UP", justify="right", style="cyan")
    table.add_column("DOWN", justify="right", style="magenta")
    table.add_column("Paired", justify="right", style="yellow")
    table.add_column("Imbalance", justify="right")
    table.add_column("Cost", justify="right")
    table.add_column("Edge P&L", justify="right", style="bold green")
    
    total_edge = 0
    for r in pnl_results:
        short_slug = r['slug'][-25:] if len(r['slug']) > 25 else r['slug']
        imb_style = "red" if abs(r['imbalance']) > 2 else "white"
        edge_style = "green" if r['edge_pnl'] > 0 else "red"
        
        table.add_row(
            short_slug,
            f"{r['up_shares']:.1f}",
            f"{r['down_shares']:.1f}",
            f"{r['paired']:.1f}",
            f"[{imb_style}]{r['imbalance']:+.1f}[/{imb_style}]",
            f"${r['total_cost']:.2f}",
            f"[{edge_style}]${r['edge_pnl']:.4f}[/{edge_style}]"
        )
        total_edge += r['edge_pnl']
    
    table.add_row("", "", "", "", "", "[bold]TOTAL[/bold]", f"[bold]${total_edge:.4f}[/bold]")
    return table

def stream_loop(client, slug_filter, interval):
    """Main streaming loop."""
    console.print(f"[bold green]Streaming trades from ClickHouse[/bold green] (refresh every {interval}s)")
    console.print(f"Filter: {slug_filter or 'ALL'}")
    console.print("Press Ctrl+C to stop\n")
    
    while True:
        try:
            console.clear()
            console.print(f"[dim]Last update: {datetime.now().strftime('%H:%M:%S')}[/dim]\n")
            
            # Fetch and display trades
            trades = fetch_recent_trades(client, slug_filter)
            if trades.result_rows:
                console.print(build_trades_table(trades))
            else:
                console.print("[yellow]No recent trades found[/yellow]")
            
            console.print()
            
            # Fetch and display P&L
            positions = fetch_pnl_summary(client, slug_filter)
            if positions.result_rows:
                pnl = calculate_pnl(positions)
                console.print(build_pnl_table(pnl))
            else:
                console.print("[yellow]No position data[/yellow]")
            
            time.sleep(interval)
            
        except KeyboardInterrupt:
            console.print("\n[bold red]Stopped[/bold red]")
            break
        except Exception as e:
            console.print(f"[red]Error: {e}[/red]")
            time.sleep(interval)

def main():
    parser = argparse.ArgumentParser(description='Stream live trades and P&L from ClickHouse')
    parser.add_argument('--interval', '-i', type=int, default=5, help='Refresh interval in seconds')
    parser.add_argument('--slug', '-s', type=str, default=None, help='Filter by market slug (partial match)')
    parser.add_argument('--minutes', '-m', type=int, default=60, help='Look back N minutes for P&L')
    args = parser.parse_args()
    
    try:
        client = get_client()
        console.print("[green]Connected to ClickHouse[/green]")
        stream_loop(client, args.slug, args.interval)
    except Exception as e:
        console.print(f"[red]Failed to connect: {e}[/red]")
        console.print("[dim]Make sure ClickHouse is running on localhost:8123[/dim]")

if __name__ == '__main__':
    main()
