# nqCore

Automated options trading service for NIFTY weekly synthetic positions. The decision engine that takes AmiBroker signals and executes orders on Zerodha Kite Connect, with persistent state and a Thymeleaf operator dashboard.

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 (Microsoft Build OpenJDK 21 LTS, G1GC) + virtual threads |
| Framework | Spring Boot 4.0.6 (JAR packaging, embedded Tomcat 11) |
| Persistence | SQLite via Hibernate ORM 7.2 (community dialect) |
| UI | Thymeleaf (`signalHome.html` + `tradeLog.html`) |
| Broker API | Kite Connect 4.0.0 (via `KiteGateway` interface — mockable) |
| Build | Maven 3.9 (shade plugin produces a single fat jar) |

## The bigger picture — nqCore is one of three cooperating apps

```
┌─────────────────────────────────────────────────────────────────────┐
│ amibroker-automator (Python + PyInstaller)                          │
│  - Windows scheduled task does daily forced reboot                  │
│  - On startup: launches AmiBroker → nqCore → nqTicker               │
│  - Handles Zerodha Kite 2FA login + Gmail OTP fetch                 │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │ spawns
                ┌──────────────────┴──────────────────┐
                ▼                                     ▼
   ┌──────────────────────┐               ┌────────────────────────────┐
   │ AmiBroker (Velocity) │               │ nqTicker (this repo's      │
   │  - AFL strategies    │               │   sibling at ../nqTicker)  │
   │  - Sends signals →   │──── HTTP ────▶│                            │
   │    /api/longEntry,   │               │  Multi-process JVM:        │
   │    /api/shortEntry,  │               │  - GatewayMain (AccelPix   │
   │    /api/flip,        │               │    poller → Chronicle Q)   │
   │    /api/longExit,    │               │  - SqliteTickWriter        │
   │    /api/shortExit    │               │  - ProfitRecenterConsumer  │
   └──────────┬───────────┘               │  - OpenBufferConsumer      │
              │                           │  - RolloverTrigger         │
              │                           │  - LogAggregatorService    │
              ▼                           │                            │
   ┌──────────────────────┐               │  Reads signals.db, calls   │
   │ nqCore (this repo)   │◀──── HTTP ────│  nqCore /arm-buffer at     │
   │                      │     :9192     │  09:15 IST                 │
   │  Receives signal     │               └────────────────────────────┘
   │  → ComputeUtil       │
   │  → Position*Service  │
   │  → KiteGateway       │
   │  → SQLite + Kite     │
   └──────────────────────┘
```

- **amibroker-automator** lives at `C:/Users/autotrading/Documents/amibroker-automator/`
- **nqTicker** lives at `C:/Users/autotrading/Documents/nqTicker/`
- They share `signals.db` and `nifty_ticks.db` under `C:/novaquant/data/sqlite/`

## Build & Run

```bash
# Local (mock + test DB — no broker calls, no production data)
mvn clean package
java -jar target/nqCore-2026.05.jar --spring.profiles.active=mock,test

# Production (real Kite, real DB)
mvn clean package -Pproduction
java -jar target/nqCore-2026.05.jar  # defaults to 'live' profile per spring.profiles.default
```

Open [http://localhost:8080/signalHome](http://localhost:8080/signalHome). On startup, JDK 21 logs Tomcat's request handlers as `tomcat-handler-N` (virtual threads) instead of `http-nio-N` (platform threads).

### Profiles

| Profile | DB | Broker | Use case |
|---|---|---|---|
| `live` (default) | `signals.db` | Real Kite | Production |
| `mock` | `signals.db` | MockKiteGateway (synthetic LTP/quotes/orders) | Local dev without Kite credentials |
| `mock,test` | `signals_test.db` | MockKiteGateway | The 43-test integration suite |
| `production` (Maven profile) | — | — | Excludes `MockKiteGateway.java` from the jar |

## Architecture

### Signal flow (synchronous critical path)

```
AmiBroker HTTP → SignalController → SignalService
  ├── handleTradeOpen   → PositionOpeningService.openTrade()
  ├── handleTradeClose  → PositionClosingService.closeTrade()
  │                       └── WeeklySymbolService.checkAndPromoteRolloverSymbol()
  ├── handleFlip        → PositionClosingService.closeTrade() + 
  │                       PositionOpeningService.openTrade()
  └── handleRollOver    → PositionRolloverService.rollOver()
```

All position legs of a trade fan out concurrently. The fan-out uses **`PositionUtil.LEG_EXEC`**, a shared `Executors.newVirtualThreadPerTaskExecutor()` static field — blocking Kite calls park their virtual thread instead of holding a platform thread. The same executor backs `CompletableFuture.supplyAsync` for parallel margin calculations.

### Order execution — graduated LIMIT walk

`PositionUtil.placeAggressiveOrder` routes via flag `order.execution.use-limit-walk` (default `true`):

1. **Pre-flight quote** (`KiteGateway.getQuote`) — if bid/ask spread is wider than `MAX_SPREAD_PCT` or quotes are stale, fall straight through to MARKET. Prevents the LIMIT walk from being suckered into a one-sided book.
2. **Place LIMIT at mid** rounded to `NIFTY_OPT_TICK` (0.05).
3. **Walk** the LIMIT price 3 steps toward the opposite side (`{500ms, 1500ms, 2500ms}` with aggression `{0.25, 0.50, 1.00}` of spread), modifying the open order each step.
4. **MARKET fallback** for any unfilled quantity once the walk is exhausted, or on any exception path. A leg can never end up un-hedged.

Large orders above `MAX_SIZE_PER_ORDER` (Kite's freeze-quantity ceiling) are auto-sliced into multiple MARKET orders by `placeMarketCore` and bypass the LIMIT walk path. Slice-level fills are recorded in `leg_fill` with `slice_index` so the avg fill is reconstructable. Comma-separated order IDs in `weekly_leg.close_order_id` indicate a partial-LIMIT + MARKET-fallback hybrid.

### Per-leg PnL accounting

`ComputeUtil.calcPnL` populates both per-leg and position-level fields on every close:

| Field | Where | Formula |
|---|---|---|
| `weekly_leg.actual_pnl` | per leg | `quantity × (sell_fill_price − buy_fill_price)` |
| `weekly_leg.expected_pnl` | per leg | `quantity × (sell_intended_price − buy_intended_price)` |
| `weekly_leg.pnl_capture_pct` | per leg | `actual_pnl / expected_pnl × 100`, or `"N/A"` when expected = 0 |
| `position.actual_pnl` | trade | `Σ(leg.actual_pnl) − total_charges` |
| `position.expected_pnl` | trade | `qty × position.pointsPnl` (NIFTY-spot based — see [UPGRADE_PLAN.md](UPGRADE_PLAN.md) for the deferred sum-of-legs reconciliation) |

### Rollover (weekly Tuesday expiry)

Two trigger paths:

1. **`RollOverTriggerController`** — nqTicker's `RolloverTriggerConsumer` calls `/api/rollover-trigger?currentPrice=<close>` at 14:47 IST. Server-side guards: (a) `rolloverDay` configured, (b) today's IST date equals it, (c) `rolloverComplete = false`. If all pass: invokes `SignalService.handleRollOver(price)` → `WeeklySymbolService.markRolloverComplete()`.
2. **Synchronous promotion on next close** — `WeeklySymbolService.checkAndPromoteRolloverSymbol()` runs after every `closeTrade()`. If today is rollover day AND `rolloverComplete = false`, copies `rolloverSymbol → thisWeekSymbol` before the next open. Guarantees subsequent opens use the new week's symbol even if the 14:47 trigger missed.

Positions held across rollover accumulate `entrySpot` and `exitSpot` (each new week's spot is ADDED to the existing values). See test 5.6 in `test_runner.py`.

### Profit recenter (liquidity-aware exit)

`ProfitRecenterService.realizeProfits` (triggered by nqTicker's `ProfitRecenterConsumer` after Gate 1 + Gate 2 conditions): closes all live legs at current LTP, accumulates `realized_points`, then opens new ATM legs at the new spot. See `EXECUTION_IMPROVEMENT_PLAN.md` in `../nqTicker/` for the gate logic.

### Async post-trade hooks

`PostTradeService` runs on a dedicated thread pool (`postTradeExecutor`, single-threaded by design — see `AsyncConfig`):

- **`afterOpen`** — fetches actual execution prices → `ComputeUtil.calcMarginAndBrokerage` → save
- **`afterClose`** — fetches execution prices → `ComputeUtil.calcPnL` (sets per-leg + position PnL) → save

Margin API calls inside `calcMarginAndBrokerage` parallel-fetch real-vs-opposite via `CompletableFuture.supplyAsync(..., PositionUtil.LEG_EXEC)`. The single-thread `postTradeExecutor` is intentional — it prevents recursion deadlock with the LEG_EXEC margin futures.

### Kite gateway abstraction

`KiteGateway` is an interface with two implementations:

| Class | Profile | Behavior |
|---|---|---|
| `KiteConnectGateway` | `live` | Wraps the real Kite Connect SDK |
| `MockKiteGateway` | `mock` | Returns synthetic LTP/quote/order data; assigns `MOCK-NNNN` order IDs |

`KiteConnect` instance is cached daily by `KiteAuthService` (avoid DB hit on every API call); cache invalidates when new auth is saved.

### Persistence

Hibernate ddl-auto = `update` — schema evolves with the entity classes automatically. Tables:

- `position` — one row per trade (status, direction, entry/exit spot, PnL, charges)
- `weekly_leg` — one row per CE/PE leg of a position
- `leg_fill` — slice-level fills (supports partial LIMIT + MARKET fallback)
- `weekly_symbol_config` — current + next-week NIFTY symbol, rollover state
- `leg_template` — strike-offset matrix for position sizing (per direction × tranche)
- `trade_capital` — running capital + possible-lots config
- `kite_auth_details` — daily Kite session tokens

### Position sizing

Configured via the Position Size Matrix UI (Trade Sizing tab). Supports up to 4 tranches per direction stored in `leg_template`:

| Column | Strike offset from ATM |
|---|---|
| ATM | 0 |
| OFFSET1 | ± 50 |
| OFFSET2 | ± 100 |
| OFFSET3 | ± 150 |

LONG: BUY CE at ATM − offset (ITM), SELL PE at ATM + offset. SHORT: mirror.

### Dependency injection

All Spring beans use **constructor injection** (`private final` fields, single constructor). `@Autowired` field injection is not used. `@Value` properties are injected via constructor parameters.

## Testing

The integration suite lives in `../nqTicker/test_runner.py` (intentionally cross-repo since it tests the contract). 43 tests across 8 groups:

| Group | What it covers |
|---|---|
| 1 — Trade Lifecycle | LONG/SHORT entry, normal exit, flip, 09:15 long-exit delegation |
| 2 — Signal Validation | duplicates, idempotency, multi-strategy isolation |
| 3 — Profit Recenter | gate conditions, multi-recenter accumulation, post-rollover |
| 4 — Execute-Close | admin force-close endpoint |
| 5 — Rollover | symbol promotion, rolloverComplete guard, price accumulation |
| **6 — Per-leg PnL Fields** *(new)* | `expected_pnl`, `pnl_capture_pct`, `actual_pnl` populated on every leg |
| **7 — signal_at Normalization** *(new)* | no legacy AmiBroker date formats, all rows in `DD-MM-YYYY HH:MM:SS.SSS` |
| **8 — LIMIT Walk Execution** *(new)* | flip with LIMIT walk enabled completes, order IDs captured |

```bash
# 1. Start a mock instance on a non-prod port
mvn -DskipTests clean package
java -jar target/nqCore-2026.05.jar --spring.profiles.active=mock,test --server.port=8090 > logs/mock.log 2>&1 &

# 2. Seed test DB (lots=1) + clear caches
python -c "
import sqlite3, urllib.request
sqlite3.connect('C:/novaquant/data/sqlite/signals_test.db').execute('UPDATE leg_template SET lots=1').connection.commit()
urllib.request.urlopen(urllib.request.Request('http://localhost:8090/api/clearCache', method='POST'))
"

# 3. Point test_runner at port 8090 and run
cd ../nqTicker
sed -i "s|BASE = 'http://localhost:8080'|BASE = 'http://localhost:8090'|" test_runner.py
python test_runner.py
# Expected: PASSED: 43  FAILED: 0  TOTAL: 43

# 4. Revert BASE
sed -i "s|BASE = 'http://localhost:8090'|BASE = 'http://localhost:8080'|" test_runner.py
```

## Configuration

`src/main/resources/application.properties` — base config (DB path, ticker URL, execution flags).
`src/main/resources/application-mock.properties` — mock-profile overrides (dummy Kite credentials).
`src/main/resources/application-test.properties` — test-profile overrides (redirects to `signals_test.db`).
`src/main/resources/application-credentials.properties` — gitignored; copy from `.template` and fill in real Kite API keys.

Key flags:

```properties
order.execution.use-limit-walk=true    # graduated LIMIT walk vs pure MARKET
spring.threads.virtual.enabled=true    # Tomcat + @Async on virtual threads (Java 21)
nq.ticker.url=http://localhost:9192    # OpenBufferConsumer endpoint
```

## Key files

```
src/main/java/path/to/_40c/nqCore/
  NQCore.java                                — Spring Boot main
  AsyncConfig.java                           — single-threaded postTradeExecutor

  controller/
    SignalController.java                    — POST /api/longEntry, /api/shortEntry, /api/flip, /api/longExit, /api/shortExit
    RollOverTriggerController.java           — GET /api/rollover-trigger (called by nqTicker)
    KiteAuthController.java                  — auth UI + symbol/position-size CRUD
    EquityCurveController.java               — equity curve + capital API
    PositionLogController.java               — Trade Log dashboard page

  service/
    SignalService.java                       — signal routing + rollover promotion
    PositionOpeningService.java              — open-leg fan-out (LEG_EXEC)
    PositionClosingService.java              — close-leg fan-out (LEG_EXEC)
    PositionRolloverService.java             — weekly rollover (LEG_EXEC × 2 sites)
    ProfitRecenterService.java               — liquidity-aware recenter (LEG_EXEC × 2 sites)
    PostTradeService.java                    — async @Async hooks (afterOpen/afterClose)
    KiteAuthService.java                     — Kite session caching
    WeeklySymbolService.java                 — symbol promotion, rollover state
    WeeklySymbolCache.java                   — symbol cache
    LegTemplateCache.java                    — position-size matrix cache
    TradeCapitalService.java                 — capital CRUD + lot calcs
    EquityCurveService.java                  — equity curve aggregation

  util/
    PositionUtil.java                        — LEG_EXEC executor, placeAggressiveOrder, placeGraduatedLimit, placeMarketCore
    ComputeUtil.java                         — PnL, margin, brokerage, outcome
    Constants.java                           — app-wide constants (NIFTY_OPT_TICK, MAX_SIZE_PER_ORDER, etc.)

  gateway/
    KiteGateway.java                         — interface (getLTP, getQuote, placeOrder, modifyOrder, cancelOrder, getOrderHistory, …)
    KiteConnectGateway.java                  — real impl (live profile)
    MockKiteGateway.java                     — synthetic impl (mock profile)

  entity/
    Position.java                            — trade row (incl. normalizeSignalTime helper)
    WeeklyLeg.java                           — CE/PE leg
    LegFill.java                             — slice-level fill records
    WeeklySymbolConfig.java                  — current week + next week + rollover state
    LegTemplate.java                         — position-size tranches
    TradeCapital.java                        — running capital
    KiteAuthDetails.java                     — daily Kite session

  pojo/                                       — DTOs (LegOrder, ExecResult, OpenPrep)
  repo/                                       — Spring Data JPA repositories

src/main/resources/
  templates/signalHome.html                  — main operator dashboard
  templates/tradeLog.html                    — historical trade log
  application.properties                     — base config
  application-mock.properties                — mock-profile overrides
  application-test.properties                — test-profile overrides (signals_test.db)
  logback-spring.xml                         — async appender, daily rolling file

UPGRADE_PLAN.md                              — history of Java 21 + virtual threads + Spring Boot 4 upgrade
afl/rollOverTrigger.afl                      — AmiBroker AFL: fires GET /api/rollover-trigger at 14:47 IST
```

## Deploy pattern

Production lives at `C:/novaquant/nqCore/`. Deploy is a jar swap:

```bash
# 1. Build production jar
mvn -DskipTests -Pproduction clean package

# 2. Stop, backup, swap, restart
PID=$(netstat -ano | grep "0.0.0.0:8080.*LISTENING" | awk '{print $5}' | head -1)
powershell -Command "Stop-Process -Id $PID -Force"
cp C:/novaquant/nqCore/nqCore-2026.05.jar C:/novaquant/nqCore/nqCore-2026.05.jar.prev
cp target/nqCore-2026.05.jar C:/novaquant/nqCore/nqCore-2026.05.jar
cd C:/novaquant/nqCore && java -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &

# 3. Verify
until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/trades | grep -q '200'; do sleep 2; done
```

**Never deploy during market hours (09:15–15:30 IST Mon–Fri) or while a position is LIVE without an explicit operator override.** Rollback is `cp .prev .jar` + restart.
