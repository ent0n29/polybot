# Polybot

**Open-source Polymarket trading infrastructure and strategy reverse-engineering toolkit.**

Polybot provides a complete trading infrastructure for [Polymarket](https://polymarket.com) prediction markets, along with powerful tools to analyze and reverse-engineer successful trading strategies from any user.

![Strategy Analysis Dashboard](docs/showcase_readme.png)

## Features

### Trading Infrastructure
- **Executor Service**: Low-latency order execution with paper trading simulation
- **Strategy Service**: Pluggable strategy framework for automated trading
- **Real-time Market Data**: WebSocket integration for order book and trade feeds
- **Position Management**: Automatic tracking, settlement, and token redemption
- **Risk Management**: Configurable limits, kill switches, and exposure caps

### Strategy Research & Reverse Engineering
- **User Trade Analysis**: Ingest and analyze any Polymarket user's trading history
- **Pattern Recognition**: Identify entry/exit signals, sizing rules, and timing patterns
- **Replication Scoring**: Compare your bot's decisions against target strategies
- **Backtesting Framework**: Test strategies against historical data

### Analytics Pipeline
- **ClickHouse Integration**: High-performance time-series analytics
- **Event Streaming**: Kafka-based event pipeline for real-time analysis
- **Monitoring**: Grafana dashboards and Prometheus metrics

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Polybot Architecture                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Strategy   │  │   Executor   │  │      Ingestor        │  │
│  │   Service    │──│   Service    │  │      Service         │  │
│  │              │  │              │  │                      │  │
│  │ • Strategies │  │ • Order Mgmt │  │ • User Trades        │  │
│  │ • Signals    │  │ • Simulator  │  │ • Market Data        │  │
│  │ • Positions  │  │ • Settlement │  │ • On-chain Events    │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│         │                 │                    │                 │
│         └─────────────────┼────────────────────┘                 │
│                           │                                      │
│                    ┌──────▼──────┐                               │
│                    │    Kafka    │                               │
│                    │   Events    │                               │
│                    └──────┬──────┘                               │
│                           │                                      │
│                    ┌──────▼──────┐                               │
│                    │ ClickHouse  │                               │
│                    │  Analytics  │                               │
│                    └─────────────┘                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- Docker & Docker Compose
- Python 3.11+ (for research tools)

### 1. Clone and Configure

```bash
git clone https://github.com/yourusername/polybot.git
cd polybot

# Copy environment template
cp .env.example .env

# Edit .env with your configuration
# At minimum, set POLYMARKET_TARGET_USER to analyze
```

### 2. Start Infrastructure

```bash
# Start ClickHouse and Kafka
docker-compose -f docker-compose.analytics.yaml up -d

# Optional: Start monitoring stack
docker-compose -f docker-compose.monitoring.yaml up -d
```

### 3. Build and Run Services

```bash
# Build all services
mvn clean package -DskipTests

# Start executor (paper trading mode by default)
cd executor-service && mvn spring-boot:run -Dspring-boot.run.profiles=develop

# Start strategy service (in another terminal)
cd strategy-service && mvn spring-boot:run -Dspring-boot.run.profiles=develop

# Start ingestor (in another terminal) - ingests target user's trades
cd ingestor-service && mvn spring-boot:run -Dspring-boot.run.profiles=develop
```

### 4. Research & Analysis

```bash
cd research

# Create Python virtual environment
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Take a snapshot of target user's data
python snapshot_report.py

# Run deep analysis
python deep_analysis.py

# Compare your bot's execution vs target
python sim_trade_match_report.py
```

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `POLYMARKET_TARGET_USER` | Username to analyze/replicate | Yes (for research) |
| `POLYMARKET_PRIVATE_KEY` | Wallet private key | For live trading |
| `POLYMARKET_API_KEY` | API credentials | For live trading |
| `ANALYTICS_DB_URL` | ClickHouse connection | For analytics |

See [.env.example](.env.example) for complete configuration reference.

### Trading Modes

| Mode | Description |
|------|-------------|
| `PAPER` | Simulated trading (default) |
| `LIVE` | Real money trading |

## Services

### Executor Service (Port 8080)
Handles order execution, position management, and settlement.

```bash
# API Examples
curl http://localhost:8080/api/polymarket/health
curl http://localhost:8080/api/polymarket/positions
curl http://localhost:8080/api/polymarket/settlement/plan
```

### Strategy Service (Port 8081)
Runs trading strategies and generates signals.

```bash
curl http://localhost:8081/api/strategy/status
```

### Ingestor Service (Port 8082)
Ingests market data and user trades into ClickHouse.

### Analytics Service (Port 8083)
Provides analytics APIs over ClickHouse data.

## Included Strategy: Complete-Set Arbitrage

The repository includes a fully-implemented **complete-set arbitrage strategy** for Polymarket Up/Down binary markets:

- **Edge Detection**: Identifies when UP + DOWN prices sum to less than $1
- **Inventory Skewing**: Adjusts quotes to balance positions
- **Fast Top-Up**: Quickly completes pairs after partial fills
- **Taker Mode**: Crosses spread when edge is favorable

See [docs/EXAMPLE_STRATEGY_SPEC.md](docs/EXAMPLE_STRATEGY_SPEC.md) for detailed documentation.

## Research Tools

The `research/` directory contains Python tools for strategy analysis:

| Script | Purpose |
|--------|---------|
| `snapshot_report.py` | Take data snapshots for analysis |
| `deep_analysis.py` | Comprehensive strategy analysis |
| `replication_score.py` | Score how well you're replicating |
| `sim_trade_match_report.py` | Compare sim vs target execution |
| `paper_trading_dashboard.py` | Jupyter dashboard for monitoring |

## Project Structure

```
polybot/
├── executor-service/       # Order execution & settlement
├── strategy-service/       # Trading strategies
├── ingestor-service/       # Data ingestion
├── analytics-service/      # Analytics APIs
├── polybot-core/           # Shared libraries
├── research/               # Python analysis tools
├── docs/                   # Documentation
└── monitoring/             # Grafana/Prometheus configs
```

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Ideas for Contribution
- New trading strategies
- Additional market types support
- Improved analytics and visualizations
- Better backtesting framework
- More reverse-engineering tools

## Disclaimer

**This software is for educational and research purposes only.**

- Trading prediction markets involves significant financial risk
- Past performance does not guarantee future results
- You are solely responsible for your trading decisions
- Always start with paper trading before using real funds

## License

MIT License - see [LICENSE](LICENSE) for details.

---

**Built with curiosity about how successful traders operate on Polymarket.**
