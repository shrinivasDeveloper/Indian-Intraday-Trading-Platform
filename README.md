# Indian Intraday Trading Platform
## Single Spring Boot App — Java 17 | kiteconnect 3.5.1 | PostgreSQL | Redis

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker Desktop (for Postgres + Redis)
- Zerodha Kite Connect API account

---

## Step 1 — Install kiteconnect JAR (REQUIRED FIRST)

kiteconnect 3.5.1 is NOT on Maven Central. Run once:

```bash
chmod +x install-kiteconnect.sh
./install-kiteconnect.sh
```

Or manually:
1. Download `kiteconnect-3.5.1.jar` from https://github.com/zerodha/javakiteconnect/tree/master/dist
2. Run:
```bash
mvn install:install-file \
  -Dfile=kiteconnect-3.5.1.jar \
  -DgroupId=com.zerodhatech.kiteconnect \
  -DartifactId=kiteconnect \
  -Dversion=3.5.1 \
  -Dpackaging=jar
```

---

## Step 2 — Set credentials

```bash
cp .env.example .env
# Edit .env with your real Zerodha credentials
```

---

## Step 3 — Start PostgreSQL and Redis

```bash
docker-compose up postgres redis -d
# Wait for "database system is ready to accept connections"
```

Flyway will automatically create all tables on first run.

---

## Step 4 — Build and Run

```bash
# Option A: Maven
mvn clean package -DskipTests
mvn spring-boot:run

# Option B: Full Docker stack
docker-compose up --build
```

App starts on http://localhost:8080

---

## Step 5 — Daily Login (every trading day)

The app starts but trading is paused until you authenticate with Zerodha.

**Option A — Manual login (recommended for first time):**
1. Open: http://localhost:8080/api/auth/login-url
2. Complete Zerodha login in browser
3. Copy the `request_token` from the redirect URL
4. POST to:
```bash
curl -X POST "http://localhost:8080/api/auth/token?requestToken=YOUR_REQUEST_TOKEN"
```

**Option B — Automated login:**
Set in .env:
```
ZERODHA_AUTO_LOGIN=true
ZERODHA_TOTP_SECRET=your_base64_totp_secret
```

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET  | /api/auth/login-url    | Get Zerodha login URL |
| POST | /api/auth/token        | Submit request_token after login |
| GET  | /api/auth/status       | Check authentication status |
| DELETE | /api/auth/logout     | Logout and clear token |
| POST | /api/backtest/run      | Run historical backtest |
| GET  | /actuator/health       | App health check |
| GET  | /actuator/prometheus   | Prometheus metrics |

---

## Backtest Example

```bash
curl -X POST "http://localhost:8080/api/backtest/run?\
symbol=RELIANCE&\
instrumentToken=408065&\
startDate=2025-01-01&\
endDate=2025-03-31&\
capital=100000&\
strategy=BREAKOUT"
```

---

## Monitoring

- Grafana: http://localhost:3000 (admin / admin123)
- Prometheus: http://localhost:9090
- App health: http://localhost:8080/actuator/health

---

## Trading Pipeline (automatic after login)

```
Zerodha WebSocket
       ↓  tick
CandleAggregatorService  →  CandleCompleteEvent
       ↓
MarketRegimeService      →  updates currentRegime
SectorStrengthService    →  updates sector scores (every 15 min)
TechnicalAnalysisService →  updates S/R zones + VWAP
PatternDetectionService  →  detects Triple Top/Bottom, Breakout, Rejection
StockScannerService      →  6-filter Chain of Responsibility → ScannerSignalEvent
       ↓
ProbabilityEngineService →  weighted score (0–100) → ProbabilityScoreEvent
       ↓ (score ≥ 80 = EXECUTE)
RiskManagementService    →  circuit breaker + sector gate → TradeApprovedEvent
       ↓
PositionSizerService     →  1% risk formula + margin check
TradeExecutionService    →  places entry + SL-M orders on Zerodha
TrailingStopLossService  →  moves SL as price advances
PartialFillHandlerService→  handles partial fills via order updates
NotificationService      →  Telegram + Slack alerts
```

---

## Circuit Breaker (auto-stops trading)

- Max 2 trades per day
- Max 1 trade per sector
- Daily loss > 3%  → trading paused
- Weekly loss > 6% → trading paused
- Monthly loss > 12% → trading paused
- Resets: daily at 08:45 IST, weekly Monday, monthly 1st
