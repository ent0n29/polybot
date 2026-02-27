# Agents

## Cursor Cloud specific instructions

### Prerequisites (installed by VM snapshot)

- **Java 21** (OpenJDK, pre-installed on base image)
- **Maven 3.8+** (`sudo apt-get install -y maven`)
- **Docker + Docker Compose** (Docker CE 28.x with `fuse-overlayfs` storage driver and `iptables-legacy` for nested container support)

### Docker setup caveat

Docker runs inside a nested container (Docker-in-Docker). After the daemon starts, you **must** ensure the socket is accessible:

```bash
sudo dockerd &>/dev/null &
sleep 3
sudo chmod 666 /var/run/docker.sock
```

Without `chmod 666 /var/run/docker.sock`, the `infrastructure-orchestrator-service` will fail to start Docker Compose stacks because it runs `docker compose` as the `ubuntu` user.

### Build and test

See `README.md` for standard commands. Key ones:

- `mvn clean package -DskipTests` — build all 6 modules (~20s)
- `mvn test` — run tests (~45s; analytics-service test takes ~30s due to Spring context load attempting ClickHouse connection, but still passes)

### Starting services

1. **Start Docker daemon** (if not already running): `sudo dockerd &>/dev/null & sleep 3 && sudo chmod 666 /var/run/docker.sock`
2. **Start infrastructure containers**: `docker compose -f docker-compose.analytics.yaml up -d` (Redpanda + ClickHouse)
3. **Start services** individually with `--spring.profiles.active=develop`, or use `./start-all-services.sh` which builds, starts infra via the orchestrator, then starts all 5 services in background. Service JARs are in `<module>/target/<module>-0.0.1-SNAPSHOT.jar`.
4. The `infrastructure-orchestrator-service` (port 8084) starts both the analytics and monitoring Docker Compose stacks on boot. If you start Docker containers manually first, the orchestrator will detect them and skip re-creation.

### Service ports

| Service | Port |
|---|---|
| executor-service | 8080 |
| strategy-service | 8081 |
| analytics-service | 8082 |
| ingestor-service | 8083 |
| infrastructure-orchestrator-service | 8084 |

### Non-obvious notes

- **Alertmanager** may restart-loop in this environment due to missing/invalid `alertmanager.yml` config (e.g., SLACK_WEBHOOK_URL not set). This is benign — all other monitoring services work fine. The infra-orchestrator reports "DEGRADED" health for the monitoring stack but continues normally.
- The `develop` profile activates **PAPER** trading mode with a simulated order execution engine. No real API keys are needed for development.
- All services publish events to Kafka (Redpanda on `localhost:9092`), which are consumed and stored in ClickHouse (`localhost:8123`, database `polybot`).
- The strategy-service connects to live Polymarket WebSockets for market data even in paper mode, so internet access is required for the strategy to discover markets and stream TOB data.
- Logs go to `logs/` directory when using `start-all-services.sh` or when services are started with output redirected there.
