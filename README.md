# nqCore

Automated options trading system for NIFTY weekly synthetic positions, built on Spring Boot 3.5.9 + Thymeleaf + JPA (SQLite) + Kite Connect.

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17 |
| Framework | Spring Boot 3.5.13 (JAR packaging) |
| UI | Thymeleaf (`signalHome.html`) |
| Persistence | SQLite via Hibernate (community dialect) |
| Broker API | Kite Connect 4.0.0 |

## Build & Run

```bash
mvn clean package
java -jar target/nqCore-2026.04.jar
```

Open [http://localhost:8080/signalHome](http://localhost:8080/signalHome)

### Mock mode (no broker connection)

Run with the `mock` profile to test without Kite Connect credentials:

```bash
java -jar target/nqCore-2026.04.jar --spring.profiles.active=mock
```

Mock mode swaps `KiteConnectGateway` for `MockKiteGateway` (returns synthetic LTP/order data). All service logic — including rollover promotion, capital calculations, and post-trade PnL — runs normally. New DB columns (`rollover_day`, `rollover_complete`) are created automatically via `ddl-auto=update`; no schema migration needed.

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
  │                      └── checkAndPromoteRolloverSymbol() (if rollover day)
  ├── handleFlip       → closeTrade()
  │                      └── checkAndPromoteRolloverSymbol() (if rollover day)
  │                      └── openTrade()          ← always sees updated symbol
  └── handleRollOver   → TradeRollOverService.rollOver()
```

Order placement runs in parallel (`IntStream.parallel`) per leg. Large-lot orders (> 1755 qty) are auto-sliced by Kite.

### Rollover flow

Weekly symbol rollover is a two-step process:

1. **AFL trigger** — `afl/rollOverTrigger.afl` runs on a Nifty-i 1-min chart. At exactly 14:47 IST it fires once per day (guarded by `StaticVarGet/Set` keyed on `DateNum()`), calling:

   ```
   GET /api/rollover-trigger?currentPrice=<close>
   ```

2. **Server-side** — `RollOverTriggerController` applies three guards before acting:
   - No rollover day configured → skip
   - Today (IST) ≠ configured rollover day → skip
   - `rolloverComplete = true` → skip (already done)

   If all guards pass: calls `SignalService.handleRollOver(price)` then `SymbolService.markRolloverComplete()`.

3. **Symbol promotion on trade close/flip** — `SignalService.checkAndPromoteRolloverSymbol()` is called synchronously after every trade close. On rollover day, if `rolloverComplete` is false, it copies `rolloverSymbol → thisWeekSymbol` before the next open order is placed. This guarantees the opening service always uses the promoted symbol.

### Rollover UI (signalHome — Save Symbols section)

| Field | Type | Behaviour |
|---|---|---|
| Rollover Day | `<input type="date">` | Calendar-only picker (keyboard blocked). Saved to `rollover_day` column. Saving new symbols resets `rolloverComplete` to false. |
| Rollover Complete? | Read-only text | Displays YES (green) / NO (grey) from `rollover_complete` column. Updated automatically by `markRolloverComplete()`. |

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

### Dependency injection

All Spring beans use **constructor injection** (`private final` fields, single constructor). `@Autowired` field injection is not used. `@Value` properties are injected via constructor parameters.

## Key Files

```
afl/
  rollOverTrigger.afl           — AmiBroker AFL: fires GET /api/rollover-trigger at 14:47 IST

src/main/java/path/to/_40c/
  controller/
    SignalController.java           — webhook receiver (POST /signal)
    KiteAuthController.java         — auth + position size + symbol UI
    RollOverTriggerController.java  — rollover AFL endpoint (GET /api/rollover-trigger)
    EquityCurveController.java      — equity curve + capital API
  service/
    SignalService.java              — signal routing + rollover promotion logic
    TradeOpeningService.java        — order placement (open)
    TradeClosingService.java        — order placement (close + large-move stub)
    TradeRollOverService.java       — weekly rollover
    PostTradeService.java           — async post-trade calculations
    KiteAuthService.java            — Kite session management
    SymbolService.java              — symbol config, cache, rollover promotion
    TradeCapitalService.java        — capital CRUD + lot-size calculations
    EquityCurveService.java         — equity curve data aggregation
  util/
    TradeUtil.java                  — KiteConnect wrapper (LTP, orders, margin, cache)
    ComputeUtil.java                — PnL, outcome, capital calculations
    Constants.java                  — app-wide constants + Kite credentials
  entity/
    SymbolConfig.java               — symbol table (thisWeekSymbol, rolloverSymbol,
                                      rolloverDay, rolloverComplete)
  AsyncConfig.java                  — @EnableAsync + postTradeExecutor bean

src/main/resources/
  templates/signalHome.html     — main UI (auth, symbols, position size, analytics)
  application.properties
  application-mock.properties   — mock profile overrides
  logback-spring.xml            — async appender, log rotation
```
