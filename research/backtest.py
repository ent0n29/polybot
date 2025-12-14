from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import numpy as np
import pandas as pd

Scenario = Literal["actual", "mid", "exec_proxy", "all_maker", "all_taker"]


def find_repo_root(start: Path | None = None) -> Path:
    start = start or Path.cwd()
    for p in [start, *start.parents]:
        if (p / "research" / "snapshot.py").exists():
            return p
    raise RuntimeError(
        "Cannot locate repo root (expected to find `research/snapshot.py`). "
        "Start Jupyter from the repo root or set REPO_ROOT=/path/to/polybot."
    )


def pick_latest_snapshot_dir(*, snapshots_root: Path, username: str) -> Path:
    candidates = sorted(snapshots_root.glob(f"{username}-*"))
    if not candidates:
        raise RuntimeError(f"No snapshots found under {snapshots_root} for username={username}")
    return max(candidates, key=lambda p: p.stat().st_mtime)


@dataclass(frozen=True)
class LoadedFeatures:
    snapshot_path: Path
    features: pd.DataFrame


def load_features(*, username: str, snapshot_dir: str | None = None) -> LoadedFeatures:
    repo_root = Path(os.getenv("REPO_ROOT")) if os.getenv("REPO_ROOT") else find_repo_root()
    snapshots_root = repo_root / "research" / "data" / "snapshots"

    snapshot_path = Path(snapshot_dir) if snapshot_dir else pick_latest_snapshot_dir(snapshots_root=snapshots_root, username=username)
    features_path = snapshot_path / "features.parquet"
    if not features_path.exists():
        raise RuntimeError(f"Missing {features_path}; run 02_feature_layer_and_regimes.ipynb first.")

    df = pd.read_parquet(features_path)
    return LoadedFeatures(snapshot_path=snapshot_path, features=df)


def _as_float(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce")


def compute_entry_price(df: pd.DataFrame, *, scenario: Scenario, fallback_to_actual: bool = True) -> pd.Series:
    """
    Returns the assumed execution entry price per trade row under a scenario.

    Scenarios:
    - actual: use observed price (df['price'])
    - mid: use TOB mid (df['mid'])
    - exec_proxy: use exec_type to choose bid/ask/mid vs actual:
        - BUY: MAKER_LIKE -> bid, TAKER_LIKE -> ask, INSIDE -> mid, else -> actual
        - SELL: MAKER_LIKE -> ask, TAKER_LIKE -> bid, INSIDE -> mid, else -> actual
    - all_maker: assume BUY fills at bid / SELL fills at ask (upper bound; ignores fill probability)
    - all_taker: assume BUY pays ask / SELL hits bid (lower bound; ignores price improvement)
    """
    side = df.get("side", pd.Series(["BUY"] * len(df))).astype(str)
    actual = _as_float(df["price"])

    bid = _as_float(df.get("best_bid_price"))
    ask = _as_float(df.get("best_ask_price"))
    mid = _as_float(df.get("mid"))

    if scenario == "actual":
        entry = actual.copy()
    elif scenario == "mid":
        entry = mid.copy()
    elif scenario == "all_maker":
        entry = pd.Series(np.where(side.eq("BUY"), bid, ask), index=df.index)
    elif scenario == "all_taker":
        entry = pd.Series(np.where(side.eq("BUY"), ask, bid), index=df.index)
    elif scenario == "exec_proxy":
        exec_type = df.get("exec_type", pd.Series(["UNKNOWN"] * len(df))).astype(str)
        entry = actual.copy()

        buy = side.eq("BUY")
        sell = side.eq("SELL")

        entry = entry.where(~(buy & exec_type.eq("MAKER_LIKE")), bid)
        entry = entry.where(~(buy & exec_type.eq("TAKER_LIKE")), ask)
        entry = entry.where(~(buy & exec_type.eq("INSIDE")), mid)

        entry = entry.where(~(sell & exec_type.eq("MAKER_LIKE")), ask)
        entry = entry.where(~(sell & exec_type.eq("TAKER_LIKE")), bid)
        entry = entry.where(~(sell & exec_type.eq("INSIDE")), mid)
    else:
        raise ValueError(f"unknown scenario: {scenario}")

    entry = entry.where(entry > 0)
    if fallback_to_actual:
        entry = entry.fillna(actual)
    return entry

def compute_trade_pnl(df: pd.DataFrame, *, scenario: Scenario, fallback_to_actual: bool = True) -> pd.Series:
    """
    Trade-level resolution PnL for each row, using different execution-price assumptions.

    Notes:
    - This is accurate for BUY-only flows (gabagool22 appears to be BUY-only).
    - If SELL trades exist, a proper position ledger is needed to compute realized PnL.
    """
    if scenario == "actual" and "realized_pnl" in df.columns:
        realized = _as_float(df["realized_pnl"])
        # If present, trust ClickHouse-derived realized_pnl (settlement PnL).
        if realized.notna().any():
            return realized

    side = df.get("side", pd.Series(["BUY"] * len(df))).astype(str)
    size = _as_float(df["size"])
    settle = _as_float(df["settle_price"])
    entry = compute_entry_price(df, scenario=scenario, fallback_to_actual=fallback_to_actual)

    pnl_buy = size * (settle - entry)
    pnl_sell = size * (entry - settle)
    pnl = pd.Series(np.where(side.eq("SELL"), pnl_sell, pnl_buy), index=df.index)

    return pnl.where(np.isfinite(pnl))


def compute_trade_cost(df: pd.DataFrame, *, scenario: Scenario, fallback_to_actual: bool = True) -> pd.Series:
    entry = compute_entry_price(df, scenario=scenario, fallback_to_actual=fallback_to_actual)
    size = _as_float(df["size"])
    cost = size * entry
    return cost.where(np.isfinite(cost) & (cost > 0))


def build_units(
    df: pd.DataFrame,
    *,
    pnl_col: str = "pnl",
    ts_col: str = "ts",
    end_col: str = "end_date",
    group_cols: list[str] | None = None,
) -> pd.DataFrame:
    """
    Aggregates trade rows into "decision units" for resampling/backtesting.
    Default grouping is (market_slug, bucket, regime) when present.
    """
    df = df.copy()

    ts = pd.to_datetime(df.get(ts_col), errors="coerce", utc=True)
    end = pd.to_datetime(df.get(end_col), errors="coerce", utc=True)
    df["pnl_ts"] = end.where(end.notna(), ts)

    if group_cols is None:
        group_cols = [c for c in ["market_slug", "bucket", "regime"] if c in df.columns]
        if not group_cols:
            group_cols = ["transaction_hash"] if "transaction_hash" in df.columns else ["event_key"]

    agg = (
        df.groupby(group_cols, dropna=False, as_index=False)
        .agg(
            pnl=(pnl_col, "sum"),
            pnl_ts=("pnl_ts", "max"),
            trades=(pnl_col, "size"),
        )
        .sort_values("pnl_ts")
        .reset_index(drop=True)
    )
    return agg


def equity_curve(units: pd.DataFrame, *, pnl_col: str = "pnl") -> pd.Series:
    pnl = _as_float(units[pnl_col]).fillna(0.0).to_numpy()
    return pd.Series(np.cumsum(pnl), index=units.index)


def max_drawdown(pnl: np.ndarray) -> float:
    equity = np.cumsum(pnl)
    peak = np.maximum.accumulate(equity)
    dd = peak - equity
    return float(np.nanmax(dd)) if dd.size else 0.0


def summarize_pnl(pnl: np.ndarray) -> dict[str, float]:
    pnl = pnl[np.isfinite(pnl)]
    if pnl.size == 0:
        return {"n": 0.0, "sum": 0.0, "mean": float("nan"), "std": float("nan")}
    return {
        "n": float(pnl.size),
        "sum": float(pnl.sum()),
        "mean": float(pnl.mean()),
        "std": float(pnl.std(ddof=1)) if pnl.size > 1 else 0.0,
        "max_drawdown": max_drawdown(pnl),
    }


def block_bootstrap(
    pnl: np.ndarray,
    *,
    iters: int = 20_000,
    block_len: int = 50,
    seed: int = 7,
) -> dict[str, dict[str, float]]:
    """
    Circular block bootstrap over time-ordered pnl[].
    Returns quantiles for total PnL and max drawdown.
    """
    pnl = pnl[np.isfinite(pnl)]
    n = pnl.size
    if n == 0:
        return {}

    block_len = max(1, int(block_len))
    iters = max(1, int(iters))

    rng = np.random.default_rng(seed)
    totals = np.empty(iters, dtype=float)
    max_dd = np.empty(iters, dtype=float)

    for i in range(iters):
        idx = []
        while len(idx) < n:
            start = int(rng.integers(0, n))
            block = (start + np.arange(block_len)) % n
            idx.extend(block.tolist())
        idx = np.asarray(idx[:n], dtype=int)
        sample = pnl[idx]
        totals[i] = float(sample.sum())
        max_dd[i] = max_drawdown(sample)

    def q(x: np.ndarray) -> dict[str, float]:
        return {
            "p01": float(np.quantile(x, 0.01)),
            "p05": float(np.quantile(x, 0.05)),
            "p50": float(np.quantile(x, 0.50)),
            "p95": float(np.quantile(x, 0.95)),
            "p99": float(np.quantile(x, 0.99)),
        }

    return {
        "total_pnl": q(totals),
        "max_drawdown": q(max_dd),
    }
