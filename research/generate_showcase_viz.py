#!/usr/bin/env python3
"""
Generate a showcase visualization for the Polybot README.
Creates a professional multi-panel dashboard showing trading analytics.
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.gridspec import GridSpec
from pathlib import Path
from datetime import datetime, timedelta
import warnings
warnings.filterwarnings('ignore')

# Set style for dark theme (looks great on GitHub)
plt.style.use('dark_background')

def load_data():
    """Load the latest snapshot data."""
    snapshots_dir = Path(__file__).parent / 'data' / 'snapshots'
    latest = sorted([d for d in snapshots_dir.iterdir() if d.is_dir()])[-1]
    print(f"Loading data from: {latest.name}")

    trades = pd.read_parquet(latest / 'trades.parquet')
    trades['ts'] = pd.to_datetime(trades['ts'])
    return trades, latest.name

def create_showcase_visualization(trades, snapshot_name):
    """Create a professional multi-panel visualization."""

    # Create figure with custom layout
    fig = plt.figure(figsize=(16, 10), facecolor='#0d1117')
    gs = GridSpec(3, 3, figure=fig, hspace=0.35, wspace=0.3,
                  left=0.06, right=0.98, top=0.92, bottom=0.08)

    # Color palette (GitHub-friendly)
    colors = {
        'profit': '#3fb950',
        'loss': '#f85149',
        'primary': '#58a6ff',
        'secondary': '#8b949e',
        'accent': '#d29922',
        'btc': '#f7931a',
        'eth': '#627eea'
    }

    # Title
    fig.suptitle('Strategy Analysis Dashboard',
                 fontsize=24, fontweight='bold', color='white', y=0.98)

    # =========================================================================
    # Panel 1: Cumulative P&L (top left, spans 2 columns)
    # =========================================================================
    ax1 = fig.add_subplot(gs[0, :2])

    # Calculate cumulative P&L
    trades_sorted = trades.sort_values('ts').copy()
    if 'pnl' in trades_sorted.columns:
        trades_sorted['cum_pnl'] = trades_sorted['pnl'].cumsum()
    else:
        # Estimate P&L from price and outcome
        trades_sorted['estimated_pnl'] = np.where(
            trades_sorted['outcome'] == 'Up',
            (trades_sorted.get('settle_price', 0.5) - trades_sorted['price']) * trades_sorted['size'],
            (trades_sorted.get('settle_price', 0.5) - trades_sorted['price']) * trades_sorted['size']
        )
        trades_sorted['cum_pnl'] = trades_sorted['estimated_pnl'].fillna(0).cumsum()

    # Plot
    ax1.fill_between(trades_sorted['ts'], 0, trades_sorted['cum_pnl'],
                     where=trades_sorted['cum_pnl'] >= 0,
                     color=colors['profit'], alpha=0.3)
    ax1.fill_between(trades_sorted['ts'], 0, trades_sorted['cum_pnl'],
                     where=trades_sorted['cum_pnl'] < 0,
                     color=colors['loss'], alpha=0.3)
    ax1.plot(trades_sorted['ts'], trades_sorted['cum_pnl'],
             color=colors['primary'], linewidth=2)

    ax1.axhline(y=0, color=colors['secondary'], linestyle='--', alpha=0.5)
    ax1.set_title('Cumulative P&L Over Time', fontsize=14, color='white', pad=10)
    ax1.set_xlabel('')
    ax1.set_ylabel('P&L ($)', fontsize=11, color='white')
    ax1.xaxis.set_major_formatter(mdates.DateFormatter('%m/%d'))
    ax1.tick_params(colors='white')
    ax1.grid(True, alpha=0.2)

    final_pnl = trades_sorted['cum_pnl'].iloc[-1]
    ax1.text(0.02, 0.95, f'Final: ${final_pnl:,.2f}',
             transform=ax1.transAxes, fontsize=14, fontweight='bold',
             color=colors['profit'] if final_pnl >= 0 else colors['loss'],
             verticalalignment='top')

    # =========================================================================
    # Panel 2: Trade Statistics (top right)
    # =========================================================================
    ax2 = fig.add_subplot(gs[0, 2])
    ax2.axis('off')

    total_trades = len(trades)
    total_volume = (trades['price'] * trades['size']).sum()
    avg_size = trades['size'].mean()
    unique_markets = trades['market_slug'].nunique() if 'market_slug' in trades.columns else 0

    stats_text = f"""
    Total Trades
    {total_trades:,}

    Volume
    ${total_volume:,.0f}

    Avg Size
    {avg_size:.1f} shares

    Markets
    {unique_markets}
    """

    ax2.text(0.5, 0.5, stats_text, transform=ax2.transAxes,
             fontsize=16, color='white', ha='center', va='center',
             fontfamily='monospace', linespacing=1.8)
    ax2.set_title('Key Metrics', fontsize=14, color='white', pad=10)

    # =========================================================================
    # Panel 3: Hourly Trade Heatmap (middle left)
    # =========================================================================
    ax3 = fig.add_subplot(gs[1, 0])

    trades['hour'] = trades['ts'].dt.hour
    trades['day'] = trades['ts'].dt.dayofweek

    heatmap_data = trades.groupby(['day', 'hour']).size().unstack(fill_value=0)

    # Ensure all hours are present
    for h in range(24):
        if h not in heatmap_data.columns:
            heatmap_data[h] = 0
    heatmap_data = heatmap_data.reindex(columns=range(24), fill_value=0)

    im = ax3.imshow(heatmap_data.values, cmap='Blues', aspect='auto')
    ax3.set_yticks(range(7))
    ax3.set_yticklabels(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], fontsize=9)
    ax3.set_xticks(range(0, 24, 4))
    ax3.set_xticklabels([f'{h}:00' for h in range(0, 24, 4)], fontsize=9)
    ax3.set_title('Trading Activity Heatmap', fontsize=14, color='white', pad=10)
    ax3.tick_params(colors='white')

    # =========================================================================
    # Panel 4: Price Distribution (middle center)
    # =========================================================================
    ax4 = fig.add_subplot(gs[1, 1])

    ax4.hist(trades['price'], bins=50, color=colors['primary'], alpha=0.7, edgecolor='none')
    ax4.axvline(x=0.5, color=colors['accent'], linestyle='--', linewidth=2, label='Fair Value')
    ax4.set_title('Entry Price Distribution', fontsize=14, color='white', pad=10)
    ax4.set_xlabel('Price', fontsize=11, color='white')
    ax4.set_ylabel('Count', fontsize=11, color='white')
    ax4.tick_params(colors='white')
    ax4.legend(loc='upper right', fontsize=9)
    ax4.grid(True, alpha=0.2)

    # =========================================================================
    # Panel 5: Market Breakdown (middle right)
    # =========================================================================
    ax5 = fig.add_subplot(gs[1, 2])

    if 'market_slug' in trades.columns:
        # Categorize markets
        def categorize(slug):
            if 'btc-updown-15m' in str(slug):
                return 'BTC 15m'
            elif 'eth-updown-15m' in str(slug):
                return 'ETH 15m'
            elif 'bitcoin-up-or-down' in str(slug):
                return 'BTC 1h'
            elif 'ethereum-up-or-down' in str(slug):
                return 'ETH 1h'
            return 'Other'

        trades['category'] = trades['market_slug'].apply(categorize)
        category_counts = trades['category'].value_counts()

        pie_colors = [colors['btc'], colors['eth'], '#f7931a99', '#627eea99', colors['secondary']]
        wedges, texts, autotexts = ax5.pie(
            category_counts.values,
            labels=category_counts.index,
            autopct='%1.1f%%',
            colors=pie_colors[:len(category_counts)],
            textprops={'color': 'white', 'fontsize': 10}
        )
        ax5.set_title('Market Breakdown', fontsize=14, color='white', pad=10)

    # =========================================================================
    # Panel 6: Trade Size Distribution (bottom left)
    # =========================================================================
    ax6 = fig.add_subplot(gs[2, 0])

    ax6.hist(trades['size'], bins=50, color=colors['accent'], alpha=0.7, edgecolor='none')
    ax6.set_title('Position Size Distribution', fontsize=14, color='white', pad=10)
    ax6.set_xlabel('Shares', fontsize=11, color='white')
    ax6.set_ylabel('Count', fontsize=11, color='white')
    ax6.tick_params(colors='white')
    ax6.grid(True, alpha=0.2)

    median_size = trades['size'].median()
    ax6.axvline(x=median_size, color=colors['loss'], linestyle='--',
                linewidth=2, label=f'Median: {median_size:.0f}')
    ax6.legend(loc='upper right', fontsize=9)

    # =========================================================================
    # Panel 7: Trades Per Day (bottom center-right, spans 2 columns)
    # =========================================================================
    ax7 = fig.add_subplot(gs[2, 1:])

    trades['date'] = trades['ts'].dt.date
    daily_trades = trades.groupby('date').size()

    ax7.bar(range(len(daily_trades)), daily_trades.values,
            color=colors['primary'], alpha=0.7, edgecolor='none')
    ax7.set_title('Daily Trade Volume', fontsize=14, color='white', pad=10)
    ax7.set_xlabel('Day', fontsize=11, color='white')
    ax7.set_ylabel('Trades', fontsize=11, color='white')
    ax7.tick_params(colors='white')
    ax7.grid(True, alpha=0.2, axis='y')

    # X-axis labels (show every few days)
    n_days = len(daily_trades)
    step = max(1, n_days // 10)
    ax7.set_xticks(range(0, n_days, step))
    ax7.set_xticklabels([str(d) for d in daily_trades.index[::step]],
                        rotation=45, ha='right', fontsize=8)

    avg_daily = daily_trades.mean()
    ax7.axhline(y=avg_daily, color=colors['accent'], linestyle='--',
                linewidth=2, label=f'Avg: {avg_daily:.0f}/day')
    ax7.legend(loc='upper right', fontsize=9)

    # =========================================================================
    # Footer
    # =========================================================================
    fig.text(0.5, 0.02,
             'Polybot - Open Source Polymarket Trading Infrastructure & Strategy Research',
             ha='center', fontsize=11, color=colors['secondary'], style='italic')

    return fig

def main():
    print("Loading trading data...")
    trades, snapshot_name = load_data()
    print(f"Loaded {len(trades):,} trades")

    print("Generating visualization...")
    fig = create_showcase_visualization(trades, snapshot_name)

    # Save as high-quality PNG
    output_path = Path(__file__).parent.parent / 'docs' / 'showcase.png'
    output_path.parent.mkdir(exist_ok=True)
    fig.savefig(output_path, dpi=150, facecolor='#0d1117', edgecolor='none',
                bbox_inches='tight', pad_inches=0.2)
    print(f"Saved to: {output_path}")

    # Also save a smaller version for README
    readme_path = Path(__file__).parent.parent / 'docs' / 'showcase_readme.png'
    fig.savefig(readme_path, dpi=100, facecolor='#0d1117', edgecolor='none',
                bbox_inches='tight', pad_inches=0.2)
    print(f"Saved README version to: {readme_path}")

    plt.close()
    print("Done!")

if __name__ == '__main__':
    main()
