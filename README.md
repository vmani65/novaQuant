# NovaQuant

Automated options trading system for NIFTY weekly synthetic positions, built on Spring Boot 3.5.9 + Thymeleaf + JPA (SQLite) + Kite Connect.

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17 |
| Framework | Spring Boot 3.5.9 (JAR packaging) |
| UI | Thymeleaf (`signalHome.html`) |
| Persistence | SQLite via Hibernate (community dialect) |
| Broker API | Kite Connect 3.5.1 |

## Build & Run

```bash
mvn clean package
java -jar target/novaquant-v3.jar
```

Open [http://localhost:8080/signalHome](http://localhost:8080/signalHome)

## Configuration

Update `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:sqlite:/path/to/signals.db
```

API keys and user credentials live in `src/main/java/path/to/_40c/util/Constants.java`.

## Architecture

### Signal Flow (critical path — fully synchronous)

```
TradingView webhook → SignalController → SignalService
  ├── handleTradeOpen  → TradeOpeningService.openTrade()
  ├── handleTradeClose → TradeClosingService.closeTrade()
  ├── handleFlip       → closeTrade() + openTrade()
  └── handleRollOver   → TradeRollOverService.rollOver()
```

Order placement runs in parallel (`IntStream.parallel`) per leg. Large-lot orders (> 1755 qty) are auto-sliced by Kite.

### Post-trade calculations (async — off the critical path)

After every open/close/flip, `PostTradeService` runs on a dedicated thread pool (`postTradeExecutor`, 2–4 threads):

- **afterOpen** — fetches actual execution prices → calculates margin & brokerage → saves
- **afterClose** — fetches execution prices → calculates outcome / PnL / capital → saves

Margin API calls inside `calcMarginAndBrokerage` run in parallel via `CompletableFuture` on `ForkJoinPool.commonPool()` (separate pool to avoid deadlock with `postTradeExecutor`).

### Kite session

`TradeUtil.getKiteConnectObject()` caches the `KiteConnect` instance for the current day (avoids a DB hit on every order/LTP/margin call). Cache is invalidated automatically when new auth is saved.

### Position sizing

Configured via the Position Size Matrix UI (Trade Sizing tab). Supports up to 4 tranches per direction:

| Column | Strike offset |
|---|---|
| ATM | 0 |
| OFFSET1 | ATM ± 50 |
| OFFSET2 | ATM ± 100 |
| OFFSET3 | ATM ± 150 |

LONG: CE leg buys at ATM − offset (ITM). SHORT: PE leg buys at ATM + offset (ITM).

## Key Files

```
src/main/java/path/to/_40c/
  controller/
    SignalController.java       — webhook receiver (POST /signal)
    KiteAuthController.java     — auth + position size UI
  service/
    SignalService.java          — signal routing
    TradeOpeningService.java    — order placement (open)
    TradeClosingService.java    — order placement (close + large-move stub)
    TradeRollOverService.java   — weekly rollover
    PostTradeService.java       — async post-trade calculations
    KiteAuthService.java        — Kite session management
  util/
    TradeUtil.java              — KiteConnect wrapper (LTP, orders, margin, cache)
    ComputeUtil.java            — PnL, outcome, capital calculations
    Constants.java              — app-wide constants + Kite credentials
  AsyncConfig.java              — @EnableAsync + postTradeExecutor bean

src/main/resources/
  templates/signalHome.html     — main UI (auth, symbols, position size, analytics)
  application.properties
  logback-spring.xml            — async appender, log rotation
```
