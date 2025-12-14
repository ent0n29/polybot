# Research (Jupyter)

This folder is for offline quant research (Step 4) using ClickHouse as the source of truth.

## Prereqs

- ClickHouse running locally (`docker-compose.analytics.yaml`)
- ClickHouse DDL applied (creates `polybot.user_trade_research`):
  - `./scripts/clickhouse/apply-init.sh`

## Setup (recommended)

### Using `uv` (recommended)

```bash
cd research
uv venv
uv pip install -r requirements.txt
```

### Using `venv` (alternative)

```bash
cd research
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Run

```bash
cd research
uv run jupyter lab
```

Open `research/notebooks/01_extract_snapshot.ipynb`.

Then:

- `research/notebooks/02_feature_layer_and_regimes.ipynb`
- `research/notebooks/03_model_and_tests.ipynb`
- `research/notebooks/04_backtest_and_montecarlo.ipynb`

## Connection defaults

The notebook/script defaults to:

- ClickHouse HTTP: `http://127.0.0.1:8123`
- Database: `polybot`
- User: `intellij` (no password; granted SELECT in ClickHouse init)

Override via env vars:

- `CLICKHOUSE_HOST` (default `127.0.0.1`)
- `CLICKHOUSE_PORT` (default `8123`)
- `CLICKHOUSE_DATABASE` (default `polybot`)
- `CLICKHOUSE_USER` (default `intellij`)
- `CLICKHOUSE_PASSWORD` (default empty)
