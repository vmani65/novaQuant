# nqCore — Deferred Upgrade Plan

> **Purpose.** This is a self-contained execution plan for upgrading nqCore's runtime (Java 17 → 21), Spring Boot framework (3.5.13 → 4.0.6), and progressively replacing the order critical path's polling primitives with virtual-thread + event-driven equivalents. It is written so a fresh AI agent or developer can execute each phase end-to-end without breaking core trading logic. Each phase is independent and can be deployed in isolation.
>
> **Phase map (high-level):**
> - **A** — Java 17 → 21 runtime upgrade + `spring.threads.virtual.enabled` (DEPLOYED 2026-06-02).
> - **B** — Virtual-thread executor (`LEG_EXEC`) for parallel leg ops, replacing FJP common pool.
> - **C** — Spring Boot 3.5.13 → 4.0.6 framework upgrade.
> - **D₁** — One-shot quote fetch: collapse 3 Kite quote API calls into 1 per signal (~80ms saved).
> - **D₂** — WebSocket order-update stream replaces LIMIT-walk sleep-then-poll (~400ms saved per leg).
>
> A, B, C are the "infrastructure" phases (runtime, parallelism, framework). D₁/D₂ are the "critical-path latency" phases enabled by A+B. Do them in order, one at a time.
>
> **Last updated.** 2026-06-03. Phase D₁ + D₂ plans appended after critical-path analysis of trade #31. The Spring Boot 4.0.6 jar was already built + mock-validated (34/34 tests PASS) on 2026-06-01; the pom.xml change was reverted to keep current prod stable while the freshly deployed graduated-LIMIT walk validates over a few trading days.

---

## ⚠️ Critical context — read before doing ANY work below

This is a **live production trading system** for NIFTY weekly synthetic options on Zerodha. Indian equity markets are open **09:15–15:30 IST Mon–Fri**.

**Hard rules:**

1. **NEVER deploy during market hours.** Check the clock and the day-of-week. Weekend or evening only.
2. **NEVER deploy while there's a LIVE position.** Verify with:
   ```sql
   SELECT COUNT(*) FROM position WHERE status='LIVE';
   -- Must return 0 before proceeding
   ```
   Production DB: `c:/novaquant/data/sqlite/signals.db`
3. **NEVER skip the mock test suite.** It's the only safety net before live deploy. Suite is at `c:/Users/autotrading/Documents/nqTicker/test_runner.py` (34 tests).
4. **NEVER change the graduated LIMIT walk logic** (`PositionUtil.placeGraduatedLimit`). It's freshly deployed and being live-validated. Touching it before validation invalidates the experiment.
5. **NEVER touch the nqTicker repo** as part of these upgrades. Phases A/B/C are nqCore-only.
6. **NEVER edit `application-credentials.properties`** (Kite API keys, gitignored).
7. **DO NOT do code modernization for its own sake** — pattern matching for switch, sequenced collections, record patterns, etc. were explicitly evaluated on 2026-06-01 and rejected. They improve readability but not execution speed. Out of scope.
8. **Rollback path exists for every phase via `.prev` jar swap.** Use it the moment something looks wrong.

---

## Deployment pattern (memorize, reused in every phase)

Production deploy folder: `C:/novaquant/nqCore/`
- `nqCore-2026.05.jar` — currently running
- `nqCore-2026.05.jar.prev` — last-working version, ready for instant rollback
- `stdout.log` / `stderr.log` — runtime logs

### Standard deploy steps

```bash
# 1. Build PRODUCTION profile (excludes MockKiteGateway from the jar)
cd c:/Users/autotrading/Documents/nqCore
mvn -DskipTests -Pproduction clean package
# Output: target/nqCore-2026.05.jar (~73-75 MB depending on Spring Boot version)

# 2. Confirm MockKiteGateway is NOT in the jar
find target/classes -name "Mock*"
# Expected: no output (mock excluded)

# 3. Stop running prod
PID=$(netstat -ano | grep "0.0.0.0:8080.*LISTENING" | awk '{print $5}')
powershell -Command "Stop-Process -Id $PID -Force"
sleep 2

# 4. Backup + swap
cp C:/novaquant/nqCore/nqCore-2026.05.jar C:/novaquant/nqCore/nqCore-2026.05.jar.prev
cp target/nqCore-2026.05.jar C:/novaquant/nqCore/nqCore-2026.05.jar

# 5. Start new jar (no flags needed — JDK_JAVA_OPTIONS is set machine-wide)
#    Machine env: JDK_JAVA_OPTIONS=-XX:+UseZGC -XX:+ZGenerational
#    The JVM auto-applies it; you'll see "NOTE: Picked up JDK_JAVA_OPTIONS:" in stderr.
cd C:/novaquant/nqCore
java -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &

# 6. Wait + verify
until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/trades 2>/dev/null | grep -q '200'; do sleep 2; done
grep "Started NQCore" stdout.log | tail -1
curl -s http://localhost:8080/api/trades | python -c "import sys,json; print(len(json.load(sys.stdin)), 'positions')"
grep -c ERROR stdout.log  # Should be 0
```

### Rollback (when something breaks)

**⚠ IMPORTANT** — `JDK_JAVA_OPTIONS=-XX:+UseZGC -XX:+ZGenerational` is set machine-wide. JDK 17 does NOT support `-XX:+ZGenerational` (it'll print `Unrecognized VM option 'ZGenerational'` and refuse to start). If rolling back to a JDK 17-compiled jar (anything before Phase A deploy on 2026-06-02), you MUST clear the env var inline.

```bash
PID=$(netstat -ano | grep "0.0.0.0:8080.*LISTENING" | awk '{print $5}')
powershell -Command "Stop-Process -Id $PID -Force"
sleep 2
cp C:/novaquant/nqCore/nqCore-2026.05.jar.prev C:/novaquant/nqCore/nqCore-2026.05.jar

# If .prev is JDK 17-compiled (pre-2026-06-02), clear JDK_JAVA_OPTIONS for this launch:
cd C:/novaquant/nqCore && JDK_JAVA_OPTIONS="" "C:/Program Files/Java/jdk-17/bin/java.exe" -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &

# If .prev is JDK 21-compiled (post-2026-06-02), just run normally — env var is picked up:
# cd C:/novaquant/nqCore && java -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &
```

### Mock test pattern (always run before live deploy)

```bash
# 1. Build DEFAULT profile (mock INCLUDED) — note: no -Pproduction
cd c:/Users/autotrading/Documents/nqCore
mvn -DskipTests clean package

# 2. Confirm MockKiteGateway IS in the jar
find target/classes -name "Mock*"
# Expected: MockKiteGateway.class present

# 3. Start mock instance on port 8090 against signals_test.db
java -jar target/nqCore-2026.05.jar --spring.profiles.active=mock,test --server.port=8090 \
     > c:/Users/autotrading/Documents/nqTicker/logs/mock-nqcore-8090.log 2>&1 &

# 4. Wait for ready
until curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/api/trades 2>/dev/null | grep -q '200'; do sleep 2; done

# 5. Seed test DB (set leg_template lots=1; mock fills exactly 1 lot per order)
python -c "
import sqlite3, urllib.request
c = sqlite3.connect('c:/novaquant/data/sqlite/signals_test.db')
c.execute('UPDATE leg_template SET lots=1')
c.commit()
req = urllib.request.Request('http://localhost:8090/api/clearCache', method='POST')
urllib.request.urlopen(req)
"

# 6. Temporarily redirect test_runner BASE to 8090 and run
cd c:/Users/autotrading/Documents/nqTicker
sed -i "s|BASE = 'http://localhost:8080'|BASE = 'http://localhost:8090'|" test_runner.py
python test_runner.py
# Expected: PASSED: 34  FAILED: 0  TOTAL: 34  /  ALL TESTS PASSED

# 7. Revert test_runner BASE
sed -i "s|BASE = 'http://localhost:8090'|BASE = 'http://localhost:8080'|" test_runner.py

# 8. Stop mock
PID=$(netstat -ano | grep "0.0.0.0:8090.*LISTENING" | awk '{print $5}')
powershell -Command "Stop-Process -Id $PID -Force"
```

If `test_runner.py` reports anything less than 34/34 PASS, **STOP** and report failures.

---

# Phase A — Java 17 → 21 upgrade

**Risk: LOW.** Spring Boot 3.5.13 supports Java 21 natively. Kite SDK 4.0.0 was compiled with JDK 23 (verified via jar manifest). Hibernate 6.6.x supports Java 21. Lombok 1.18.44+ supports Java 21. No code-level breakage expected.

**Time: ~30 minutes.**

**Pre-requisites:**
- JDK 21 installed on the host (OpenJDK 21, Zulu 21, Adoptium 21, or Microsoft Build OpenJDK 21 — all work)
- `JAVA_HOME` configured to point to JDK 21
- Verify with `java -version` → must print `21.x` before proceeding
- No LIVE positions (see hard rules above)
- Market closed
- Current prod is the SB 3.5.13 + LIMIT walk jar (verify with `unzip -p C:/novaquant/nqCore/nqCore-2026.05.jar BOOT-INF/classes/application.properties | grep use-limit-walk` → should print `true`)

## Changes in Phase A

### A.1 — `pom.xml`: bump Java version

**What:** Change `<java.version>17</java.version>` to `<java.version>21</java.version>`.

**Why:** Tells Maven's compiler plugin to target Java 21 bytecode. Required for any Java 21 language features and for virtual-thread runtime classes to be available at compile time.

**Impact:** Existing code compiles unchanged — Java 21 is backward-compatible with Java 17 source. The compiled `.class` files will have version 65.0 (Java 21) instead of 61.0 (Java 17). JVM 21 is required to run them.

**Verification after edit:** `mvn -DskipTests clean compile` must succeed without errors.

### A.2 — `application.properties`: enable Spring's virtual-thread integration

**What:** Append a new line to `c:/Users/autotrading/Documents/nqCore/src/main/resources/application.properties`:

```properties
# --- Virtual threads (Java 21+) ---
# Spring Boot 3.2+ integration. When true:
#   - Tomcat HTTP request handlers run on virtual threads
#   - Spring @Async + TaskScheduler use virtual threads
# Each inbound HTTP request gets its own virtual thread (~1KB) that yields the
# carrier platform thread during blocking I/O.
spring.threads.virtual.enabled=true
```

**NOTE:** ZGC was previously set machine-wide via `JDK_JAVA_OPTIONS`. As of 2026-06-02 ~23:00 it has been cleared — memory pressure on this 8 GB box made ZGC's higher RSS overhead unsafe. Both apps now use G1GC default. See the section "JVM startup flags" in A.3 for full context, and the incident at the bottom of this file.

**Why:** With Java 21 available, Spring's built-in virtual-thread integration is one config flag. Inbound HTTP requests (nqTicker→nqCore polling every 30s, signal endpoints, dashboard) no longer compete for the fixed Tomcat thread pool. Each request gets its own virtual thread.

**Impact:** Moderate. Helps when many concurrent inbound requests arrive (uncommon in this app — typical concurrent inbound load is 1-2 requests). NOT a fix for outbound Kite calls — those need Phase B.

**Verification after edit:** Look in startup log for "Setting up Tomcat with virtual threads" or absence of Tomcat thread pool config errors.

### A.3 — JVM startup flags: ~~Generational ZGC~~ **REVERTED to G1GC default**

**What was tried:** `JDK_JAVA_OPTIONS=-XX:+UseZGC -XX:+ZGenerational` set machine-wide.

**Why it was reverted (2026-06-02 ~23:00 IST):** On this 8 GB RAM machine, ZGC's higher RSS overhead (~250 MB extra per JVM at idle vs G1GC) combined with the 7-JVM stack (nqCore + 6 nqTicker children) pushed system memory above the OOM threshold. Two JVMs died silently in succession during the deploy chaos:
- nqCore PID 5228 died sometime between 22:01 and 22:43 (no hs_err, no event log)
- nqTicker PID 9956 died ~13 min after start (no hs_err, no event log)

Historical evidence supports memory pressure as the cause: an older `hs_err_pid9532.log` in `C:/Users/autotrading/Documents/amibroker-automator/` (dated 2026-05-11) reads: *"There is insufficient memory for the Java Runtime Environment to continue. Native memory allocation (mmap) failed to map 65536 bytes. Error detail: Failed to commit metaspace. Possible reasons: The system is out of physical RAM or swap space."* This box has a known history of JVMs dying under memory pressure, and ZGC's higher footprint was enough to retrigger it.

**Final action:** `JDK_JAVA_OPTIONS` cleared at machine scope. Both nqCore and nqTicker now launch on **JDK 21 + G1GC default** with no JVM flags. RSS per JVM dropped roughly in half (nqCore: 509 → 263 MB at startup). System is comfortably stable.

**To clear (already done — keep this for reference if ever re-set):**
```powershell
[Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS', $null, 'Machine')
```

**What we kept from the Generational ZGC experiment:** nothing — fully reverted. We still get JDK 21's other wins (virtual threads, smaller bytecode in places, language features when we decide to use them).

**If ZGC is ever wanted again:** the machine first needs more RAM, OR heap caps via `-Xmx512m` on each nqTicker child + `-Xmx1g` on nqCore. Do NOT re-enable ZGC on this box at its current 8 GB until that's done.

## Phase A execution steps (in order)

1. **Pre-flight check** (run each, must all pass):
   ```bash
   java -version | head -1   # Must print '21.x'
   python -c "import sqlite3; r = sqlite3.connect('c:/novaquant/data/sqlite/signals.db').execute(\"SELECT COUNT(*) FROM position WHERE status='LIVE'\").fetchone(); print('LIVE positions:', r[0]); assert r[0] == 0, 'BLOCKED: live position exists'"
   date  # Confirm outside 09:15-15:30 IST (or weekend)
   ```

2. **Edit `pom.xml`** — change `<java.version>17</java.version>` to `<java.version>21</java.version>`. Only that one line.

3. **Edit `application.properties`** — append the virtual-threads block (see A.2 above).

4. **Compile sanity:**
   ```bash
   cd c:/Users/autotrading/Documents/nqCore
   mvn -DskipTests clean compile 2>&1 | tail -5
   # Expected: 'BUILD SUCCESS'
   # If any 'error:' or 'ERROR' line appears, STOP and revert both edits.
   ```

5. **Run mock test suite** (see "Mock test pattern" above). Expected: **34/34 PASS**. If any fail, STOP and revert.

6. **Build production jar:**
   ```bash
   mvn -DskipTests -Pproduction clean package 2>&1 | tail -3
   # Expected: 'BUILD SUCCESS'
   ```

7. **Set the machine-wide env var ONCE** (only on first-time Phase A deploy — skip on later restarts):
   ```powershell
   [Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS','-XX:+UseZGC -XX:+ZGenerational','Machine')
   ```
   Confirm: open a fresh shell, `echo $env:JDK_JAVA_OPTIONS` should print the flags.

8. **Deploy** (standard deploy steps from "Deployment pattern" above — start command is flag-free):
   ```bash
   # ...standard steps 1-4 same...
   # Step 5 (start) — env var auto-applied:
   cd C:/novaquant/nqCore
   java -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &
   ```

9. **Post-deploy verification:**
   ```bash
   # All four should return ✓
   curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/trades   # 200
   curl -s http://localhost:8080/api/trades | python -c "import sys,json; print(len(json.load(sys.stdin)))"  # Same count as pre-deploy
   grep -E "Started NQCore" C:/novaquant/nqCore/stdout.log | tail -1  # Shows startup time
   grep -c "ERROR" C:/novaquant/nqCore/stdout.log  # 0
   grep "Picked up JDK_JAVA_OPTIONS" C:/novaquant/nqCore/stderr.log | tail -1  # ZGC flags applied
   ```

10. **Soak test:** monitor for 30 minutes during off-market hours. Pull stderr/stdout, watch for any unusual log lines, exceptions, or thread-related warnings.

## Phase A rollback

1. Stop the running process.
2. `cp C:/novaquant/nqCore/nqCore-2026.05.jar.prev C:/novaquant/nqCore/nqCore-2026.05.jar`
3. Restart **with JDK 17 explicitly and JDK_JAVA_OPTIONS cleared inline** (JDK 17 rejects `-XX:+ZGenerational`):
   ```bash
   cd C:/novaquant/nqCore && JDK_JAVA_OPTIONS="" "C:/Program Files/Java/jdk-17/bin/java.exe" -jar nqCore-2026.05.jar > stdout.log 2> stderr.log &
   ```
4. Revert pom.xml and application.properties edits in source.
5. Optionally clear the env var permanently if Phase A is being abandoned:
   ```powershell
   [Environment]::SetEnvironmentVariable('JDK_JAVA_OPTIONS',$null,'Machine')
   ```

---

# Phase B — Virtual-thread executor for parallel leg operations

**Risk: MEDIUM.** Code changes across 7 files. Pattern change is mechanical but requires careful attention to make sure no logic shifts.

**Pre-requisites:**
- **Phase A must be in stable prod for at least 3-5 trading days** before Phase B. Do not stack.
- LIMIT walk validated (i.e., observed in prod producing the expected slippage savings — fill-prices closer to intended than pre-LIMIT-walk baseline).
- No LIVE positions.
- Market closed.

**Time: ~2 hours.**

## What problem this solves

The codebase has 6 sites using `IntStream.range(0, items.size()).parallel().forEach(...)` to run blocking Kite API calls concurrently across legs. This runs on `ForkJoinPool.commonPool()` which has `cores − 1` workers. On a 4-core box that's **3 workers**. For 2-leg synthetics it's fine. For your planned 4+-strike positions, the 4th leg waits in queue.

Virtual threads (Java 21 GA) cost ~1KB each and yield the carrier platform thread during blocking I/O. Switching the executor to `Executors.newVirtualThreadPerTaskExecutor()` removes thread-pool saturation entirely.

Also: the new graduated-LIMIT walk has `Thread.sleep()` waits totaling up to 2.5 seconds per leg. On platform threads, that's 2.5s of held-thread per parallel leg. On virtual threads, the sleep parks and the carrier is freed.

## Changes in Phase B

### B.1 — Add shared virtual-thread executor to `PositionUtil`

**What:** Add a static field on `PositionUtil`:

```java
/**
 * Shared virtual-thread executor for parallel leg operations across services.
 * Used by PositionOpeningService, PositionClosingService, PositionRolloverService,
 * ProfitRecenterService — replaces FJP common pool for blocking I/O fan-out.
 * Static lifetime; virtual-thread executors hold negligible resources.
 */
public static final java.util.concurrent.ExecutorService LEG_EXEC =
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
```

**Why:** Centralized executor lifecycle. All parallel leg ops get consistent scheduling behavior. `public` so other services can reference it directly (`PositionUtil.LEG_EXEC`).

**Impact:** Adds ~1 KB to JVM memory. No CPU cost.

### B.2 — Convert 2 `CompletableFuture.supplyAsync` calls in `PositionUtil.java:185-186`

**What:** Pass `LEG_EXEC` as the executor:

```java
// BEFORE (line 185-186):
var realFuture     = CompletableFuture.supplyAsync(() -> getMarginCalculation(realParams));
var oppositeFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(oppositeParams));

// AFTER:
var realFuture     = CompletableFuture.supplyAsync(() -> getMarginCalculation(realParams), LEG_EXEC);
var oppositeFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(oppositeParams), LEG_EXEC);
```

**Why:** Margin calls were defaulting to FJP common, competing with parallel leg ops. Move to dedicated virtual-thread executor.

**Impact:** Margin calls no longer compete with leg ops for the same FJP slots. Negligible direct latency savings (only 2 calls); benefit is freeing FJP for any non-IO work that genuinely needs it.

### B.3 — Convert 6 `IntStream.parallel().forEach()` sites to virtual-thread futures

Each site follows the same mechanical transformation. Add this import at the top of each affected service file (it's likely already there):

```java
import java.util.concurrent.CompletableFuture;
import path.to._40c.nqCore.util.PositionUtil;
```

Then replace each `IntStream.range(...).parallel().forEach(...)` block with the equivalent `CompletableFuture.allOf(...)` pattern. The mechanical recipe:

```java
// BEFORE — uses FJP via parallel():
IntStream.range(0, items.size()).parallel().forEach(i -> {
    Item w = items.get(i);
    // ... blocking work on w ...
});

// AFTER — uses LEG_EXEC via virtual threads:
List<CompletableFuture<Void>> futs = items.stream()
    .map(w -> CompletableFuture.runAsync(() -> {
        // ... same blocking work on w ...
    }, PositionUtil.LEG_EXEC))
    .toList();
CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
```

**Critical rule:** ensure the loop body in the AFTER version only uses `w` (the item), not `i` (the index). All current uses of `i` in this codebase are just `items.get(i)` and convert trivially.

The 6 sites:

| # | File | Approx line | Phase of operation |
|---|---|---|---|
| 1 | `service/PositionClosingService.java` | 54 | close legs of LIVE trade |
| 2 | `service/PositionOpeningService.java` | 97 | open legs of new trade |
| 3 | `service/PositionRolloverService.java` | 65 | close legs (current week) |
| 4 | `service/PositionRolloverService.java` | 117 | open legs (rollover week) |
| 5 | `service/ProfitRecenterService.java` | 79 | close legs at recenter |
| 6 | `service/ProfitRecenterService.java` | 146 | open new legs at recenter |

**Why:** All 6 do parallel blocking Kite I/O. Moving them to virtual threads makes blocking I/O free; legs truly run in parallel regardless of CPU count.

**Impact:**
- 2-leg synthetic (today): minor improvement (FJP usually had room).
- 4-leg multi-strike (planned scale-up): wall-clock drops 30-50% on a 4-core box.
- 8+ leg scenarios: scales linearly instead of getting bottlenecked by FJP.

### B.4 — DO NOT change these in Phase B

- **`synchronized (w)` blocks** inside the leg loops (5 sites in services). Reason: in Java 21, virtual threads pin to carrier inside `synchronized`. However, all 5 `synchronized` blocks here contain only field setters on the leg object — no I/O inside, so pinning is harmless. Verify by reading each block. (JEP 491 in Java 24 removes pinning entirely; not a concern yet.)
- **`AsyncConfig.java`** — the `postTradeExecutor` is intentionally a single-threaded platform-thread executor to prevent deadlock with margin futures. Leave it alone.
- **The blocking calls themselves** (`placeAggressiveOrder`, `getLTP`, `getMarginCalculation`, etc.) — these are the I/O that virtual threads make cheap. No change needed in the called code.
- **`PositionUtil.placeGraduatedLimit`** — the LIMIT walk's `sleepMillis()` calls automatically become virtual-thread-friendly because the calling threads are now virtual. No code change inside.

## Phase B execution steps (in order)

1. **Pre-flight check** (same as Phase A: no LIVE positions, market closed, Phase A in prod).

2. **Edit `PositionUtil.java`** — add the `LEG_EXEC` field (B.1).

3. **Edit `PositionUtil.java`** lines 185-186 — add `LEG_EXEC` argument to both `supplyAsync` calls (B.2).

4. **Compile sanity:**
   ```bash
   mvn -DskipTests clean compile 2>&1 | tail -5
   # Expected: BUILD SUCCESS
   ```

5. **Edit 6 service files**, one at a time. After each file:
   - Compile (`mvn -DskipTests clean compile`)
   - Eyeball-diff the change to make sure the loop body is unchanged

6. **After all 7 files edited, run the full mock test suite** (per "Mock test pattern" above). Expected: **34/34 PASS**.

7. **Build production jar + deploy** (per "Standard deploy steps"). Keep the Phase A JVM flags (`-XX:+UseZGC -XX:+ZGenerational`).

8. **Post-deploy verification:**
   - All API endpoints return 200.
   - Watch logs during the next live trade. Order placement messages for parallel legs should appear with timestamps showing actual concurrent execution (interleaved, not sequential).

## Phase B rollback

`git checkout HEAD path/to/_40c/nqCore/util/PositionUtil.java path/to/_40c/nqCore/service/*.java`

Then deploy via the `.prev` jar swap. The Phase A changes (Java 21, ZGC flags, virtual threads property) stay intact and continue providing value.

---

# Phase C — Spring Boot 3.5.13 → 4.0.6

**Risk: LOW. Already validated.** On 2026-06-01 the parent BOM was bumped to 4.0.6, the project compiled cleanly first try, the mock suite returned 34/34 PASS. The pom.xml change was reverted afterward to keep prod stable while LIMIT walk validated.

**Time: ~30 minutes.**

**Pre-requisites:**
- Phase A complete and stable (Java 21 running). Spring Boot 4 requires Java 17 minimum, but doing C after A means you're on the latest JDK consistently.
- Phase B is optional before or after C — they're independent. Do not stack.
- LIMIT walk validated for at least 1 week of clean prod data.
- No LIVE positions.
- Market closed.

## Changes in Phase C

### C.1 — `pom.xml`: bump parent BOM

**What:** Single line:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.6</version>  <!-- was 3.5.13 -->
    <relativePath/>
</parent>
```

Also update the `<description>` line (cosmetic, no functional impact):

```xml
<description>Spring Boot 4.0.6 + Thymeleaf + JPA (SQLite) — nqCore Application</description>
```

**Why:** Spring Boot 3.x reaches EOL on its standard schedule. Spring Boot 4.0.6 is the latest GA release as of this plan's authorship. Keeps the project on a supported upstream.

**Impact:** Transitive bumps (all managed automatically by the parent BOM):
- Spring Core: 6.2.17 → 7.0.7
- Hibernate ORM: 6.6.45.Final → 7.2.12.Final
- Hibernate community-dialects: 6.6.45.Final → 7.2.12.Final (SQLiteDialect still present)
- Tomcat embedded: 10.1.53 → 11.0.21 (Servlet 6.1)
- Jackson databind: 2.21.2 (`com.fasterxml.jackson`) → 3.1.2 (`tools.jackson`)
- SQLite JDBC: 3.49.1.0 → 3.50.3.0

### C.2 — DO NOT change anything else in Phase C

- **No code changes.** Verified on 2026-06-01: pom bumped only, mock suite 34/34 PASS.
- **No application.properties changes.**
- **No Java version change** (Spring Boot 4 keeps Java 17 baseline; Java 21 from Phase A continues to work).
- **No code modernization** even though Spring 7 and Hibernate 7 introduce new APIs. The current code uses stable, idiomatic Spring Data JPA + Thymeleaf + standard annotations — none of which changed.

## Why each downstream change is safe

- **Spring Framework 7:** removed some deprecated APIs from 6.x. This codebase uses only stable Spring API surface (annotations: `@RestController`, `@GetMapping`, `@PostMapping`, `@RequestParam`, `@Value`, `@Async`, `@Transactional`, `@Component`, `@Service`, `@Bean`, `@Configuration`, `@EnableAsync`, `@Profile`; data layer: Spring Data JPA repositories with derived queries + `@Query`; HTTP layer: `RestTemplate` only via Kite SDK, not directly used in app code). Nothing removed in 7 affects this codebase.
- **Hibernate 7:** stricter session handling in some defaults. This codebase uses standard JPA (`@Entity`, `@Table`, `@MappedSuperclass`, `@OneToMany`, `@ManyToOne`, `@JoinColumn`, `@Column`, `@Transient`) plus Hibernate-specific `@Filter`/`@FilterDef`/`@ParamDef` on `Position.legs`. `@Filter` works identically in 7.x — verified by mock test pass.
- **Tomcat 11 (Servlet 6.1):** transparent. No `HttpServletRequest`/`HttpServletResponse` direct usage in our code.
- **Jackson 3:** package rename from `com.fasterxml.jackson` to `tools.jackson`. **This codebase does NOT import Jackson directly.** All JSON serialization happens via Spring's auto-config (`@RestController` return values, `@ResponseBody`). Verified by grep: `grep -rn "com.fasterxml.jackson" src/main/java` returns no matches.
- **Kite SDK 4.0.0:** compiled against Spring 6 transitively, but is binary-compatible with Spring 7 at the BOM-managed surface (only uses `RestTemplate` and `MappingJackson2HttpMessageConverter`, both of which preserve their 6.x APIs in 7.x).

## Phase C execution steps

1. **Pre-flight check** (no LIVE positions, market closed, Phase A live in prod).

2. **Edit `pom.xml`** — change parent version `3.5.13` → `4.0.6`. Optionally update `<description>`.

3. **Compile:**
   ```bash
   mvn -DskipTests clean compile 2>&1 | tail -5
   # Expected: BUILD SUCCESS, no errors
   # First build will download Spring Boot 4.0.6 BOM + dependencies (~50-100 MB cache)
   ```

4. **Verify resolved dependency versions** (sanity check the BOM did its job):
   ```bash
   mvn dependency:tree 2>&1 | grep -E "spring-core|hibernate-core|tomcat-embed-core|jackson-databind|sqlite-jdbc" | head -10
   # Expected:
   #   spring-core:7.0.7
   #   hibernate-core:7.2.12.Final
   #   tomcat-embed-core:11.0.21
   #   jackson-databind:3.1.2
   #   sqlite-jdbc:3.50.3.0
   ```

5. **Run mock test suite** — expect 34/34.

6. **Build production jar + deploy** via standard pattern. Keep Phase A JVM flags (`-XX:+UseZGC -XX:+ZGenerational`).

7. **Post-deploy verification:**
   - `/api/trades`, `/api/leg-templates`, `/api/cacheStatus` all return 200
   - Same position count as pre-deploy
   - No ERROR lines in stdout/stderr
   - Soak for 30+ minutes off-market

## Phase C rollback

Standard `.prev` jar swap. Revert pom.xml parent version to `3.5.13` in source.

---

# Phase D₁ — One-shot quote fetch (consolidate getLTP + per-leg getQuote)

**Risk: LOW.** Pure refactor — same Kite data, fewer HTTP round-trips. No new protocols, no new dependencies, no new failure modes. The `Quote` payload is a strict superset of `LTPQuote`; the per-leg `getQuote` inside `placeGraduatedLimit` already exists and is deleted by this phase.

**Pre-requisites:**
- Phase B + Phase C must be in stable prod for at least 3-5 trading days before Phase D₁. Phase B introduces virtual-thread fan-out across legs; D₁ assumes that fan-out is healthy.
- No LIVE positions.
- Market closed.

**Time: ~2 hours** (edit, compile, mock, deploy).

## What problem this solves

Each ENTRY or EXIT today makes **3 separate HTTP round-trips to Kite's quote API** when **1 would suffice**:

| Call | Where | Symbols | What we use from the response |
|---|---|---|---|
| `getLTP(both legs)` | upfront in `PositionOpeningService` / `PositionClosingService` | 2 | `lastPrice` (for intent-price logging in `buyIntendedPrice` / `sellIntendedPrice`) |
| `getQuote(leg-1)` | inside `placeGraduatedLimit` on virtual thread #1 | 1 | bid/ask of leg-1 |
| `getQuote(leg-2)` | inside `placeGraduatedLimit` on virtual thread #2 | 1 | bid/ask of leg-2 |

The Kite quote API returns the same payload shape for 1 or 50 instruments (basket call). `Quote` contains `lastTradedPrice` (same field as `LTPQuote.lastPrice`), plus `depth.buy[].price` and `depth.sell[].price`. We can fetch everything in one call.

**Observed wall-clock cost today** (trade #31 baseline): ~80ms on the slowest-leg critical path is the second quote round-trip. ENTRY total 825ms → projected ~745ms (~10% shaved).

**Bonus benefits:**
- 67% fewer Kite quote-API calls per signal — meaningful when AmiBroker fires fast flips back-to-back
- bid/ask used for LIMIT pricing is freshly fetched once, not twice — eliminates the unlikely-but-real case where leg-1 and leg-2 disagree about market state because they fetched 80ms apart

## Changes in Phase D₁

### D₁.1 — Promote `getLTP` call sites to `getQuote`

**What:** Replace `Map<String, LTPQuote>` with `Map<String, Quote>` at all 7 call sites in the entry/exit/recenter/rollover flows. Read `lastTradedPrice` instead of `lastPrice` (same data, different field name on the DTO).

**Why:** `Quote` is a strict superset of `LTPQuote`. Switching the upfront call from `getLTP` to `getQuote` makes the bid/ask data available to the parallel leg dispatch without a second round-trip.

**Sites to update** (read each carefully — adjacent code uses `q.lastPrice` which becomes `q.lastTradedPrice`):

| File | Approx line | Method |
|---|---|---|
| `service/PositionOpeningService.java` | 55 | `prepareOpen` (flip path) |
| `service/PositionOpeningService.java` | 68 | `openTrade` (standard path) |
| `service/PositionClosingService.java` | 48 | `closeTrade` |
| `service/PositionRolloverService.java` | 58 | `rollOver` close-side LTP |
| `service/PositionRolloverService.java` | 112 | `rollOver` open-side LTP |
| `service/ProfitRecenterService.java` | 70 | `realizeProfits` close-side LTP |
| `service/ProfitRecenterService.java` | 139 | `realizeProfits` open-side LTP |

The `OpenPrep` record in `PositionOpeningService` ([line 43](src/main/java/path/to/_40c/nqCore/service/PositionOpeningService.java#L43)) also changes type:
```java
// before
public record OpenPrep(List<LegOrder> pojos, Map<String, LTPQuote> ltp) {}

// after
public record OpenPrep(List<LegOrder> pojos, Map<String, Quote> quotes) {}
```

### D₁.2 — `PositionUtil.placeAggressiveOrder` / `placeGraduatedLimit` accept the pre-fetched `Quote`

**What:** Thread the leg's `Quote` down through `placeAggressiveOrder` into `placeGraduatedLimit`. Delete the internal `kiteGateway.getQuote(new String[]{key})` call at [PositionUtil.java:548](src/main/java/path/to/_40c/nqCore/util/PositionUtil.java#L548).

**Why:** Eliminates the per-leg quote fetch on the parallel virtual thread. The caller (service layer) already has the data.

**Method signature change:**
```java
// before
public ExecResult placeAggressiveOrder(String ins, String txn, int qty, String contextLabel)
private ExecResult placeGraduatedLimit(String ins, String txn, int qty, String contextLabel)

// after
public ExecResult placeAggressiveOrder(Quote q, String ins, String txn, int qty, String contextLabel)
private ExecResult placeGraduatedLimit(Quote q, String ins, String txn, int qty, String contextLabel)
```

Inside `placeGraduatedLimit`, the existing depth-validation block stays (null check, bid≤0/ask<bid/excessive-spread → MARKET fallback) — those guards now apply to the caller-supplied `Quote` instead of a freshly fetched one. Same semantics.

### D₁.3 — `PositionUtil.getLTP(String[])` deprecated

**What:** The `getLTP` wrapper at [PositionUtil.java:461](src/main/java/path/to/_40c/nqCore/util/PositionUtil.java#L461) becomes dead code after D₁.1 removes all callers (except [KiteConnectGateway.java:267](src/main/java/path/to/_40c/nqCore/gateway/KiteConnectGateway.java#L267) which uses it for the NIFTY 50 auth probe — that one stays).

**Action:** Remove `PositionUtil.getLTP` entirely. The auth probe in `KiteConnectGateway` calls `kite.getLTP(...)` directly on the Kite SDK, not through our wrapper, so it's unaffected. The `KiteGateway` interface keeps `getLTP` for that one auth-probe purpose.

### D₁.4 — DO NOT change these in Phase D₁

- **`KiteGateway` interface** — keep `getLTP` for the auth probe. Don't churn the interface.
- **`MockKiteGateway`** — both `getLTP` and `getQuote` implementations stay. Tests may still call either.
- **The `Quote` depth validation logic** inside `placeGraduatedLimit` — bid/ask sanity checks, MARKET fallback on bad spread — keep exactly as-is. Same semantics, just operating on caller-supplied data.
- **`buyIntendedPrice` / `sellIntendedPrice` write logic** — keep using LTP (now `quote.lastTradedPrice`). Don't switch this to mid-price; it's an intent log, not an execution price.

## Phase D₁ execution steps (in order)

1. **Pre-flight check** (same as earlier phases: no LIVE positions, market closed, Phase B+C live in prod).

2. **Edit `PositionUtil.java`**:
   - Change signatures of `placeAggressiveOrder` and `placeGraduatedLimit` to accept `Quote q`.
   - Delete the internal `kiteGateway.getQuote(...)` call inside `placeGraduatedLimit`.
   - Use the passed-in `q` for bid/ask extraction (existing depth-validation block runs against `q` instead of fresh fetch).
   - Remove the `getLTP(String[])` wrapper method.

3. **Compile sanity:**
   ```bash
   mvn -DskipTests clean compile 2>&1 | tail -5
   # Expected: BUILD SUCCESS
   # All 7 service-level callers will fail compile until D₁.1 is done
   ```

4. **Edit the 7 service-level call sites** (D₁.1 list above). After each file:
   - Compile (`mvn -DskipTests clean compile`)
   - Eyeball-diff to confirm no logic shifts — just type change `LTPQuote` → `Quote`, field rename `lastPrice` → `lastTradedPrice`

5. **Update `OpenPrep` record** in `PositionOpeningService` to hold `Map<String, Quote>`.

6. **Final compile:** `mvn -DskipTests clean compile` must show BUILD SUCCESS.

7. **Run mock test suite** (per "Mock test pattern"). Expected: **all tests PASS**.
   - If the suite has new tests for fill counts or quote consumption added since Phase B/C, those still pass.

8. **Build production jar + deploy** via standard pattern.

9. **Post-deploy verification:**
   - All API endpoints return 200.
   - Watch the next live trade's logs. Look for **single** `getQuote` HTTP call per signal — there should be no per-leg quote fetch line in the LIMIT-walk log.
   - Verify `buyIntendedPrice` / `sellIntendedPrice` populated as before (these now come from the new Quote-source, but values should match historical pattern).

10. **Soak** for 1 trading day minimum. Compare entry latency to pre-D₁ baseline — expect ~80ms reduction in the place-orders phase.

## Phase D₁ rollback

`git checkout HEAD path/to/_40c/nqCore/util/PositionUtil.java path/to/_40c/nqCore/service/*.java`

Then deploy via the `.prev` jar swap. The earlier phases (Java 21, virtual threads, Spring Boot 4) stay intact.

---

# Phase D₂ — WebSocket order-update stream (replace LIMIT-walk sleep-then-poll)

**Risk: MEDIUM-HIGH.** New feature touching the order critical path. Adds a persistent WebSocket connection to Kite alongside the existing nqTicker market-data WebSocket. Requires auth lifecycle handling, reconnect logic, and a REST fallback for missed-event cases. Failure modes are operationally observable but the critical path is more complex than today.

**Pre-requisites:**
- **Phase D₁ must be in stable prod for at least 1 week** before Phase D₂. D₂ builds on D₁'s `Quote`-threading.
- The graduated-LIMIT walk has been live for at least 4 weeks of clean data so the baseline "fill latency in 500ms walk steps" can be compared to post-D₂ "fill latency in WS-event ms".
- No LIVE positions.
- Market closed.

**Time: ~3-5 days** (component + integration + mock fixture + 2 mock soak cycles).

## What problem this solves

Today, [PositionUtil.java:582-600](src/main/java/path/to/_40c/nqCore/util/PositionUtil.java#L582-L600) detects fills with **sleep-then-poll**:

```java
for (int step = 0; step < WALK_DELAYS_MS.length; step++) {        // {500, 1500, 2500}
    sleepMillis(deadline - System.currentTimeMillis());            // BLIND SLEEP
    AttemptResult ar = peekOrderState(resp.orderId);               // REST poll Kite
    if (filled) return;
    kiteGateway.modifyOrder(...);                                   // walk price
}
```

**Every order minimum-pays the 500ms first-step sleep** before we know if it filled. Observed at trade #31: PE placed `13:15:02.915`, filled `13:15:03.455` — we waited the full 540ms even though the actual exchange-side fill latency was tens of milliseconds.

Kite's order-update WebSocket (`KiteTicker.setOnOrderUpdate(...)`) pushes the fill event the instant the order's state changes on the exchange side — typically 20-80ms after `placeOrder` returns. Replacing the blind sleep with an event-driven await cuts the per-leg fill-detection time from a fixed 500ms minimum to **real fill latency**.

**Expected wall-clock impact** (assuming WS healthy):

| Path | Today | After D₂ |
|---|---|---|
| Trade #31 entry (2 legs parallel) | 825ms | ~400ms |
| Typical entry/exit per leg | 500ms minimum | 30-80ms typical |
| 4-leg multi-strike entry (future) | ~825ms (FJP-bound) | ~400ms (linear in WS event latency) |

## What WebSocket gives us — the Kite SDK surface

`KiteTicker` is the Kite Connect WebSocket SDK (same dependency we already pull in via `com.zerodhatech.kiteconnect:kiteconnect:4.0.0`; the SDK ships both the REST `KiteConnect` and the WS `KiteTicker` classes).

```java
KiteTicker ticker = new KiteTicker(accessToken, apiKey);

ticker.setOnOrderUpdate(order -> {
    // Fires the instant an order's state changes — OPEN → COMPLETE, partial fills, etc.
    // 'order.orderId' identifies which order this event is for.
    // 'order.status', 'order.filledQuantity', 'order.averagePrice' carry the new state.
});

ticker.setOnConnectedListener(() -> { /* mark healthy */ });
ticker.setOnDisconnectedListener(() -> { /* mark unhealthy → REST fallback */ });
ticker.setOnErrorListener((err) -> { /* log + mark unhealthy */ });

ticker.connect();
```

**Connection model:**
- Single WebSocket per access token (we run one nqCore JVM → one connection).
- Auth: same `accessToken` used by REST. Renews via existing `KiteAuthService` flow; on token refresh we must reconnect.
- Order events flow over the same socket as tick events. We don't subscribe to ticks here (nqTicker already does that in a separate process — separate connection, separate access flow).
- Kite limits ~3 concurrent WS connections per user. nqTicker holds one; nqCore D₂ takes one. Comfortable margin.

## Architecture

### D₂.1 — New `KiteOrderStream` Spring component

**What:** New `@Component` in `path.to._40c.nqCore.gateway.KiteOrderStream` (alongside `KiteConnectGateway`, `MockKiteGateway`). Singleton, owns the `KiteTicker` lifecycle, exposes a `CompletableFuture`-based API for fill-await.

**Skeleton:**
```java
@Component
@Profile("!mock")                                  // real impl only outside mock profile
public class KiteOrderStream {
    private static final Logger log = LoggerFactory.getLogger(KiteOrderStream.class);

    private final KiteAuthService authService;     // existing service that holds access token
    private volatile KiteTicker ticker;
    private volatile boolean healthy = false;
    private final ConcurrentHashMap<String, CompletableFuture<Order>> awaiters = new ConcurrentHashMap<>();

    public KiteOrderStream(KiteAuthService authService) { this.authService = authService; }

    @PostConstruct
    public void connect() { /* build ticker, wire callbacks, connect */ }

    @PreDestroy
    public void disconnect() { /* graceful close */ }

    /** Register interest in an order's terminal state. Returns a future. */
    public CompletableFuture<Order> awaitTerminal(String orderId) { /* compute-if-absent */ }

    /** Caller drops interest (e.g. after timeout fallback to REST). */
    public void cancel(String orderId) { awaiters.remove(orderId); }

    public boolean isHealthy() { return healthy; }

    /** Internal: KiteTicker callback. */
    private void onOrderUpdate(Order o) {
        if (o == null || o.orderId == null) return;
        if (!isTerminal(o.status) && o.filledQuantity == null) return;  // ignore non-meaningful updates
        var fut = awaiters.remove(o.orderId);
        if (fut != null) fut.complete(o);
    }

    /** Internal: on auth-token refresh, tear down + rebuild. */
    @EventListener
    public void onTokenRefreshed(TokenRefreshedEvent ev) { reconnect(); }
}
```

**Where it lives in the package tree:** `src/main/java/path/to/_40c/nqCore/gateway/KiteOrderStream.java`. Same package as the existing `KiteGateway` / `KiteConnectGateway` / `MockKiteGateway` for consistency.

### D₂.2 — `MockKiteOrderStream` for the mock profile

**What:** Parallel `@Component @Profile("mock")` implementation. Simulates fill events on a timer (instant fill by default, configurable lag for negative tests).

**Why:** Mock tests need to validate the fill-await path without a real WebSocket. The mock fires `onOrderUpdate` synthetically when `MockKiteGateway.placeOrder` returns — same call-graph as production.

### D₂.3 — Wire `placeGraduatedLimit` to use the stream

**What:** Replace the sleep-then-poll loop in [PositionUtil.java:582-600](src/main/java/path/to/_40c/nqCore/util/PositionUtil.java#L582-L600) with event-driven await. Keep the walk-step modifyOrder logic on timeout. Keep the MARKET fallback at the end.

**Critical sequencing — register awaiter BEFORE placeOrder:**

```java
// before: register placeholder awaiter so we don't miss the event during the placeOrder round-trip
String pendingKey = "PENDING-" + UUID.randomUUID();
CompletableFuture<Order> awaitFut = orderStream.awaitTerminal(pendingKey);

OrderResponse resp = kiteGateway.placeOrder(params, ...);
if (resp == null || resp.orderId == null) {
    orderStream.cancel(pendingKey);
    return placeMarketCore(...);
}

// re-key the awaiter under the real orderId
orderStream.rekey(pendingKey, resp.orderId);

long t0 = System.currentTimeMillis();
for (int step = 0; step < WALK_DELAYS_MS.length; step++) {
    long deadline = t0 + WALK_DELAYS_MS[step];
    long timeoutMs = Math.max(1, deadline - System.currentTimeMillis());

    try {
        Order filled = awaitFut.get(timeoutMs, TimeUnit.MILLISECONDS);
        if (filled.filledQuantity >= qty && isTerminal(filled.status)) {
            log.info("[{}] {} LIMIT WS-fill at step {} (+{}ms) | avg={} | ids={}",
                contextLabel, ins, step, System.currentTimeMillis() - t0,
                filled.averagePrice, filled.orderId);
            return new ExecResult(filled.orderId, filled.filledQuantity, qty, filled.averagePrice, true, ORDER_COMPLETE);
        }
        // partial-fill or non-terminal: fall through to walk-modify
    } catch (TimeoutException te) {
        // didn't fill within this step's wall-clock budget
    } catch (ExecutionException | InterruptedException e) {
        log.warn("[{}] {} WS await failed: {} — falling back to REST poll", contextLabel, ins, e.getMessage());
        break;  // exit loop, MARKET fallback handles
    }

    // Belt-and-suspenders: one REST poll before walking — covers the rare missed-event case
    AttemptResult ar = peekOrderState(resp.orderId);
    if (ar.filledQty() >= qty && isTerminal(ar.status())) {
        orderStream.cancel(resp.orderId);
        return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), true, ORDER_COMPLETE);
    }

    // walk price
    double newPx = roundToTick(isBuy ? mid + halfSp * WALK_AGGRESSION[step] : mid - halfSp * WALK_AGGRESSION[step], NIFTY_OPT_TICK);
    kiteGateway.modifyOrder(resp.orderId, newPx, qty, Constants.VARIETY_REGULAR);

    // Re-arm the awaiter for the next step's await window
    awaitFut = orderStream.awaitTerminal(resp.orderId);
}

// After 2.5s, cancel + MARKET fallback (same as today)
orderStream.cancel(resp.orderId);
...
```

**Critical rules:**
- **Register before place.** The awaiter must exist *before* `placeOrder` returns, otherwise the fill event can fire between place-return and registration (race window).
- **REST poll on timeout, then walk.** Catches the rare case where the WS missed an event. Adds zero latency on the happy path (future completes first), one REST call on the unhappy path (no worse than today).
- **Re-arm awaiter after modifyOrder.** Each walk step is its own await window.
- **Stream health check.** If `orderStream.isHealthy() == false`, skip the WS path entirely and use today's sleep-then-poll logic. Single config flag turns the whole feature off.

### D₂.4 — Health-aware fallback

**What:** Add an `if (orderStream.isHealthy()) { ...WS path... } else { ...legacy sleep-poll path... }` switch at the top of `placeGraduatedLimit`'s fill-wait section.

**Why:** WebSocket disconnects happen (network blips, broker maintenance, auth refresh window). The system must degrade gracefully to today's behavior, not fail. The legacy code path stays in the source as the fallback — do **not** delete it.

### D₂.5 — Auth refresh integration

**What:** Listen for the existing `TokenRefreshedEvent` (or equivalent — verify the actual event class in `KiteAuthService`) and trigger `KiteOrderStream.reconnect()`.

**Why:** When access token rotates (every ~24 hours), the WS connection's old token becomes invalid. The connection must be torn down and rebuilt with the fresh token.

**Open question for execution time:** does `KiteAuthService` publish a Spring event today? If not, this phase adds one. Verify before coding.

### D₂.6 — DO NOT change these in Phase D₂

- **The legacy sleep-then-poll loop** — keep it in source as the fallback path. It's the safety net for WS-down cases.
- **The MARKET fallback at the end of `placeGraduatedLimit`** — leaves the position never-un-hedged. Keep exactly as-is.
- **`kiteGateway.modifyOrder` / `cancelOrder`** — these are still REST. WS is for *observing* fill events, not for placing or modifying.
- **WALK_DELAYS_MS values** — keep `{500, 1500, 2500}`. The first-step timeout is the wall-clock budget within which a fill is "fast enough" — under WS, fills come back well within 500ms, so the value is harmless. Tightening it later is a separate decision (Tier 2 #4 from the latency analysis).
- **`placeAutoSliceOrder` path** (large qty > MAX_SIZE_PER_ORDER) — already bypasses the LIMIT walk. No change needed.

## Phase D₂ execution steps (in order)

1. **Pre-flight check** (same as earlier phases).

2. **Verify SDK surface:**
   ```bash
   # Confirm KiteTicker is in the SDK jar
   jar -tf ~/.m2/repository/com/zerodhatech/kiteconnect/kiteconnect/4.0.0/kiteconnect-4.0.0.jar | grep -i KiteTicker
   ```

3. **Implement `KiteOrderStream`** (D₂.1) — start with skeleton, get it to connect in a standalone test against a non-trading time window (post 15:30 IST).

4. **Implement `MockKiteOrderStream`** (D₂.2) — wire into mock profile, ensure `MockKiteGateway.placeOrder` triggers a synthetic fill event after ~50ms.

5. **Add a mock test** in `test_runner.py` for the WS-await path:
   - Place an order, assert `placeAggressiveOrder` returns within 100ms (instead of 500ms).
   - Force `orderStream.healthy = false`, assert fallback to sleep-poll path still works.

6. **Wire `placeGraduatedLimit`** to the stream (D₂.3 + D₂.4). Keep both code paths under the health flag.

7. **Compile + full mock suite** — all tests must PASS, including the new ones.

8. **Mock soak — 2 cycles minimum:**
   - Cycle 1: WS healthy throughout. Verify all fills detected via WS.
   - Cycle 2: Mid-cycle, force `MockKiteOrderStream` unhealthy. Verify legacy fallback engages and tests still pass.

9. **Build production jar + stage** in `staged-deploys/`.

10. **Live-validation plan (operator-mediated, not unattended):**
    - Deploy at weekend or after-hours.
    - First trading day: monitor every entry/exit's logs. Look for `WS-fill at step 0 (+Xms)` log lines with X << 500. Expect ~30-80ms per leg.
    - First trading day: also verify the fallback path by checking WS-disconnect log lines (rare in normal operation).
    - One week post-deploy: pull latency stats from `position.opened_at - signal_received_at` deltas and compare to pre-D₂ baseline.

## Phase D₂ rollback

Two levels of rollback:

**Soft rollback (no jar swap):** flip the WS health flag to permanently-false via a property or a forced `setHealthy(false)` admin endpoint. Code stays deployed, behavior reverts to today's sleep-poll. Useful for "we're seeing weird WS events, kill it for now."

**Hard rollback (jar swap):** standard `.prev` jar swap as in earlier phases. Source revert: remove `KiteOrderStream` component, restore original `placeGraduatedLimit` body.

## Why this is worth the operational complexity

This is a real feature, not a refactor. The operational complexity (WS lifecycle, reconnects, missed events) is non-trivial and the failure modes are subtle. The reasons to do it anyway:

1. **400ms shaved off every entry/exit**, half the current 825ms entry budget. This is the largest single latency win available without changing the LIMIT walk's behavior.
2. **Less time un-hedged.** During a 2-leg entry, the naked-short PE side currently sits on the books for ~500ms before we know the hedge CE is filled. D₂ cuts that to ~50ms.
3. **Faster flips.** A long→short flip is close + open back-to-back. Today ~1.6s, after D₂ ~1.0s. Less spot drift between exit and re-entry.
4. **Scales linearly to multi-strike futures.** Sleep-poll has a hard 500ms floor regardless of leg count. WS-await has only fill latency floor. 4-leg and 6-leg synthetics get the same per-leg detection time as 2-leg.
5. **Tail-risk reduction.** Sleep-poll's 500ms floor is **worst on the days you'd most want it to be fast** — when Kite REST p99 spikes to 800ms. WS event latency is more uniform across normal and stressed conditions.

This phase is a deliberate cost: ~3-5 days of work + careful first-week monitoring. The payoff is structural, not cosmetic.

---

# Important do-NOT-do list for any agent executing this plan

1. **DO NOT deploy during market hours** (09:15-15:30 IST Mon-Fri). Check current time before touching anything.
2. **DO NOT proceed if there's a LIVE position** — query the DB first. If LIVE, wait until the position closes or wait until weekend.
3. **DO NOT skip the mock test suite** — it's the only pre-prod safety net.
4. **DO NOT change the `placeGraduatedLimit` logic** in PositionUtil — it's freshly deployed and being validated. Stay out.
5. **DO NOT touch the nqTicker repo** as part of these upgrades. nqCore-only changes.
6. **DO NOT do code modernization** for its own sake. Pattern matching for switch, sequenced collections, record patterns, stream-to-loop conversions — all explicitly evaluated and rejected for performance ROI.
7. **DO NOT rename DB columns or tables.** Schema is stable post-2026-05 refactor.
8. **DO NOT change the Kite SDK version.** 4.0.0 is the latest available on Maven Central.
9. **DO NOT change `application-credentials.properties`** (Kite API keys, gitignored).
10. **DO NOT bundle multiple phases into one deploy.** Each phase deploys independently. If A+B+C ever break together, you can't tell which one caused the issue.
11. **DO NOT swallow a mock test failure.** If even 1 of 34 tests fails, STOP and report. Do not deploy.
12. **DO NOT push to main / master without an explicit OK from the operator** — these are infrastructure changes, not feature work.
13. **DO NOT bundle D₁ with D₂.** D₁ is a refactor; D₂ is a feature. Each gets its own deploy + soak window. (Same rule as A/B/C — but worth restating because D₁ unblocks D₂ and the temptation to combine is real.)
14. **DO NOT delete the legacy sleep-then-poll loop** inside `placeGraduatedLimit` during D₂. It is the fallback path when the WS connection is unhealthy. Removing it leaves the order critical path with no degradation route.
15. **DO NOT enable D₂'s WebSocket on a JDK older than 21.** The per-order awaiter pattern depends on virtual threads (Phase B) to not pin platform threads while waiting on fill events. Running D₂ without virtual threads will silently saturate the carrier pool under any leg count > 2.

---

# Key file references (read-only context)

| Path | Purpose |
|---|---|
| `pom.xml` | Maven config — Phase A.1 and Phase C.1 edit here |
| `src/main/resources/application.properties` | Spring config — Phase A.2 edits here |
| `src/main/java/path/to/_40c/nqCore/util/PositionUtil.java` | Phase B.1, B.2 edits; lines 185-186 + new static field |
| `src/main/java/path/to/_40c/nqCore/util/Constants.java` | Constants (LOT_SIZE, MAX_SIZE_PER_ORDER, NIFTY_OPT_TICK) — read-only |
| `src/main/java/path/to/_40c/nqCore/service/PositionClosingService.java` | Phase B.3 site #1 — line 54 |
| `src/main/java/path/to/_40c/nqCore/service/PositionOpeningService.java` | Phase B.3 site #2 — line 97 |
| `src/main/java/path/to/_40c/nqCore/service/PositionRolloverService.java` | Phase B.3 sites #3, #4 — lines 65, 117 |
| `src/main/java/path/to/_40c/nqCore/service/ProfitRecenterService.java` | Phase B.3 sites #5, #6 — lines 79, 146 |
| `src/main/java/path/to/_40c/nqCore/AsyncConfig.java` | Spring async config — DO NOT touch |
| `src/main/java/path/to/_40c/nqCore/gateway/KiteGateway.java` | Gateway interface — D₁ keeps `getLTP` for KiteConnectGateway auth probe |
| `src/main/java/path/to/_40c/nqCore/gateway/KiteConnectGateway.java` | Real Kite wrapper — `getLTP` retained for NIFTY 50 auth probe (line 267); `getQuote` is used for D₁ |
| `src/main/java/path/to/_40c/nqCore/gateway/MockKiteGateway.java` | Mock used by `mock,test` profile — D₁ uses existing `getQuote`; D₂ needs sibling `MockKiteOrderStream` |
| `src/main/java/path/to/_40c/nqCore/gateway/KiteOrderStream.java` | **New in D₂.** Real-WS implementation. Listens to KiteTicker `onOrderUpdate` |
| `src/main/java/path/to/_40c/nqCore/gateway/MockKiteOrderStream.java` | **New in D₂.** Mock counterpart used by `mock,test` profile |
| `c:/Users/autotrading/Documents/nqTicker/test_runner.py` | 34-test mock suite |
| `c:/novaquant/data/sqlite/signals.db` | Production DB |
| `c:/novaquant/data/sqlite/signals_test.db` | Test DB (used by mock,test profile) |
| `C:/novaquant/nqCore/nqCore-2026.05.jar` | Currently deployed jar |
| `C:/novaquant/nqCore/nqCore-2026.05.jar.prev` | Previous working jar (instant rollback) |
| `C:/novaquant/nqCore/stdout.log` / `stderr.log` | Runtime logs |

---

# Phase completion log (update as phases ship)

| Phase | Date executed | PR / commit | Status |
|---|---|---|---|
| Exec-log readability (ExecTrace + MDC) | 2026-06-13 01:21 IST | (unstaged) | **DEPLOYED** (market closed; position #39 LONG LIVE held overnight — operator-authorized, same precedent as previous night's deploy). Operator-requested readability rework: parallel legs made readable without touching execution. (1) New `ExecTrace` in `PositionUtil` — each leg's full story (quote, LIMIT placed, walk steps with +ms offsets, fallbacks, fill) flushed as ONE multi-line INFO block from a `finally` in `placeAggressiveOrder`, so blocks from parallel legs can never interleave; on exception an ERROR block prints the steps already taken (incl. resting orderIds). (2) MDC tag `<context>:<instrument> \| ` via `%X{leg}` in logback patterns — every line on a leg's thread (incl. gateway classes) carries attribution. (3) Noise demoted to DEBUG: walk steps, per-fill dumps, verify chatter, post-trade fetch lines; `getOrderTrades` now emits one `fills orderId=…: N fills, qty, wAvg` INFO line (was ~1 line/fill, printed twice per order via setTradeExecutedPrices + captureSliceFills). Zero changes to order placement/timing/fallback logic. Mock-validated against exact staged jar (mock,test → signals_test.db): 10/10 scenarios PASS, 0 ERROR lines; note — suite accidentally ran 2× and trade 9 (D09) of run 2 left a LIVE test-DB row because its hardcoded `time=09:15` exit signal deduped against run 1's; confirmed harness artifact (run 1 D09 closed clean at 01:15:00.731), not a regression; suite is single-run-per-day by design. Format smoke-tested standalone on port 8085 pre-validation. Pre-deploy PID 2292 → post-deploy PID 6640, 27.9s startup, 0 ERROR, #39 LIVE preserved, WS deferred (no auth row — expected at 01:21). Jar SHA256 `b89a4344ad1101a86651d7dbb0e50fe24ed690f1afc0463d63725c04241c1f55`, staged at `staged-deploys/nqCore-2026.05-PROD-execlog-mdc.jar`, `.prev` = price-log jar (`fb95eab6…`). |
| Exec-log + tick-price cleanup | 2026-06-13 00:44 IST | (unstaged) | **DEPLOYED** (market closed; **position #39 LONG LIVE** — operator-authorized deploy with overnight position, same precedent as D₂.1; state is DB-resident, no signals possible at 00:44). Three changes in `PositionUtil.java`, operator-requested: (1) `roundToTick()` now re-rounds to 2dp after tick rounding — the binary-float product (`1842 * 0.05 = 92.10000000000001`) was flowing into the actual LIMIT/modify price sent to Kite, not just logs; 2dp re-round is exact for 0.05-tick multiples so walk prices land on identical ticks. (2) New `quote | bid= ask= ltp= mid= spread=` INFO line logged before each `LIMIT @ placed` line (ENTRY + EXIT share `placeGraduatedLimit`). (3) Spread displayed at 2dp, mid at 3dp (half-tick exact) in both lines. Mock-validated against the exact staged jar (`mock,test` profile → signals_test.db): 24/24 harness calls OK, 12/12 positions CLOSED with PnL+capital computed, 0 ERROR lines, new log format verified with clean prices (harness summary print failed only on cp1252 `₹` console encoding — pre-existing, not app). Pre-deploy PID 6468 → post-deploy PID 2292, 22.3s startup, 0 ERROR, position #39 LIVE preserved. WS deferred at startup ("no auth row for today" at 00:44 — expected; morning re-auth connects). Jar SHA256 `fb95eab66098bd923193019dc672c13d9dd8f7e087c035531a13044faf9e55af` (75,474,768 bytes), staged at `staged-deploys/nqCore-2026.05-PROD-limitwalk-pricelog.jar`, backup `.prev` = D₂.3 jar (`406a9926…`). |
| A | 2026-06-02 ~21:56 IST | (unstaged) | **DEPLOYED.** JDK 21.0.11 (Microsoft Build) + spring.threads.virtual.enabled=true. Mock suite 34/34 PASS pre-deploy. LIVE position 29 open at deploy time (operator-authorized override). Final config: **G1GC (NOT ZGC)** — ZGC was tried but rolled back after silent JVM deaths under memory pressure on the 8GB box. See incident note at bottom of this file. |
| B + C + D₁ + D₂ (bundle) | 2026-06-05 13:02-13:03 IST | (unstaged) | **DEPLOYED as bundle.** Operator override on (a) the "one phase per deploy" rule (#10/#13) and (b) the "no market-hours deploy" rule (#1) — market was open until 15:30 IST and bundle had been mock-validated 43/43 + manually verified 52/52 WS fills the prior session. Pre-deploy snapshot: 0 LIVE positions, PID 3204 (old jar) stopped cleanly, backup at `nqCore-2026.05.jar.prev` (73,359,769 bytes). Post-deploy snapshot: PID 6900, 28.6s startup, 0 ERROR lines, `KiteOrderStream: WS connected` at 13:03:09 on [ReadingThread] confirming D₂ live. Fingerprints verified: `Tomcat 11.0.21` (Phase C), `Hibernate 7.2.12.Final` (Phase C), `o.s.boot.tomcat.TomcatWebServer` (Spring Boot 4 package, Phase C), Java 21.0.11 (Phase A — already in prior deploy). Staged jar SHA256 `ddb99cf51211fc71845a7077f06d31a243e7ce7afc5b8d253ccf0646ba8db29a`. Also shipped: `?busy_timeout=5000` on SQLite JDBC URLs (mitigates D₂-exposed race between `markRolloverComplete()` and post-trade async fill-retrieval). End-to-end live signal validation pending — strategy idle since position #31 closed at 10:40 today; next signal will be the real production verification of D₁ one-shot quote + D₂ WS-await on the live Kite stream. |
| nqTicker→21 | 2026-06-02 ~22:30 IST | (unstaged in nqTicker repo) | **DEPLOYED.** Separate task authorized by operator: nqTicker `maven.compiler.source/target` bumped 17→21, rebuilt as shaded fat jar, deployed to `C:/novaquant/nqTicker/nqTicker-2026.05.jar` (old jar preserved at `.bak.prePhaseA`). All 6 processes (parent + 5 children: gateway, sqlite, rollover, recenter, log-aggregator) verified on JDK 21 + G1GC. Position 29 detected via signals.db cross-process. Ports 9191/9195 listening; 9192 open-buffer exits off-hours by design. |
| D₂.3 — Reconnect market-hours gate | 2026-06-11 19:06 IST | (unstaged) | **DEPLOYED** (clean window — market closed 15:30, 0 LIVE positions; #37 closed during the day). Refinement on D₂.2 per operator: the bounded 6-attempt retry should only run during the trading window, since outside it a WS drop is the daily ~07:00 token expiry (unfixable until morning re-auth) or after-close idle (no trading). Added `RECONNECT_WINDOW_START=09:00`/`RECONNECT_WINDOW_END=16:00` IST (buffer around 09:15–15:30 session) and `withinReconnectWindow()`; `scheduleReconnect()` now no-ops with a single "staying dormant" log line when outside the window. Critically, the gate is ONLY on the scheduled-reconnect loop — `@PostConstruct connect()` and the `KiteAuthChangedEvent → tryConnect()` path are NOT gated, so startup and morning re-auth (~08:49, before 09:00) still connect immediately. Verified post-deploy: WS connected at 19:06:08 (outside window, via startup tryConnect) confirming the gate placement. Net effect on tomorrow's 07:06 expiry: **1 "disconnected" + 1 "dormant" line, 0 attempts, 0 leaked timers** (vs D₂.1's 1,123 lines / 94 timers). PID 864 → 4956, 29.9s startup, 0 ERROR. New jar SHA256 `406a9926a90d8db7d3708ed6039e2996037262ea13d2da21aed096e0d7bb0cc5`. Supersedes the D₂.2 jar (`704e9466…`) deployed 4h earlier. |
| D₂.2 — WS reconnect storm fix | 2026-06-11 14:46 IST | (unstaged, superseded same day by D₂.3) | **DEPLOYED.** Operator override (market open till 15:30, position #37 SHORT LIVE — operator judged market quiet). Fixes a thread-leak + log-storm introduced by D₂.1: on 2026-06-11 the daily ~07:06 IST token expiry disconnected the WS, and D₂.1's reconnect loop compounded with KiteTicker's OWN internal `setTryReconnection(true)` Timer — each `tryConnect()` built a fresh ticker and orphaned the prior ticker's internal Timer (which `disconnect()` does NOT stop), leaking **94 Timer threads** that hammered the dead 403 token → **1,123 WARN lines, 1.3 MB log** in ~1h45m until the 08:47 daily restart + 08:49 re-auth cleared it. Two changes in `KiteTickerOrderStream.java`: (1) `setTryReconnection(false)` — kill KiteTicker's internal reconnect Timer so there's exactly one reconnection mechanism (our scheduler) and nothing to leak; removed the now-moot `setMaximumRetries`/`setMaximumRetryInterval`. (2) `MAX_RECONNECT_ATTEMPTS=6` — after 6 consecutive failed reconnects, go DORMANT (stop scheduling, REST fallback stays engaged) and wait for `KiteAuthChangedEvent` to wake us (the proven 75ms recovery path). Projected effect on tomorrow's 07:06 expiry: ≤~12 log lines + 0 leaked timers + dormant-until-reauth, vs 1,123 lines + 94 leaked timers. Pre-deploy PID 6776 → post-deploy PID 864, 28.8s startup, 0 ERROR, `WS connected` at 14:46:31 first attempt, position #37 LIVE preserved. New jar SHA256 `704e94666463b5b9e5e2ad7d885221357e3adf2902d0e4ee3806eb38d8f9ef9c`. Dormancy logic un-exercised at deploy (clean first connect); validates on next real disconnect. |
| D₂.1 — WS reconnect patch | 2026-06-09 21:28-21:29 IST | (unstaged) | **DEPLOYED.** Operator override on the "no LIVE position" rule — position #36 LONG was open (opened 14:18 today, won't be touched after market close at 15:30). Market was closed (deploy at 21:28 IST, ~6 hours post-close), no signals would fire during restart. Pre-deploy snapshot: PID 6252, position #36 LIVE. Post-deploy snapshot: PID 5552, 26.6s startup, 0 ERROR lines, position #36 LIVE state preserved across restart (DB unchanged). New jar SHA256 `87a90254b824a8e46403d339ba535cc09e38108e722633da1ce4ad8190025fbf` (slightly different from staged `e3c088e9…` due to build-timestamp delta; identical source). Backup at `.prev` is the prior D₁+D₂ jar (SHA `ddb99cf5…`). Empirical bug being fixed: on 2026-06-09 the WS disconnected at 09:29:12 IST and stayed dead for **~12 hours** — manual `/kite-auth/save` at 21:22 was the only way to recover; the ticker reconnected in 75ms once `KiteAuthChangedEvent` fired through the existing `onAuthChanged` listener. The patch wires that same recovery path to fire on any `OnDisconnect` with exponential backoff (5s→10s→20s→40s→60s cap), so future disconnects auto-heal without operator intervention. Fix in `KiteTickerOrderStream.java`: added `ScheduledExecutorService reconnectScheduler` + `AtomicInteger consecutiveFailures` + `shutdownRequested` flag; `OnDisconnect` listener actively schedules a fresh `tryConnect()`; follow-up health check 5s after each attempt; backoff resets to 0 on successful `OnConnect`; `@PreDestroy` sets `shutdownRequested=true` and `shutdownNow()`s the scheduler. No changes to `MockKiteOrderStream`, `KiteOrderStream` interface, or `placeGraduatedLimit` consumer. WS connected on first attempt at 21:29:22 post-startup (no reconnect logic exercised yet — will validate on next real disconnect). |

---

# Incident — Silent JVM deaths under ZGC on 2026-06-02 (~22:00-22:55 IST)

**Summary.** During the JDK 17 → 21 upgrade for nqCore (Phase A) and the parallel-authorized JDK 17 → 21 upgrade for nqTicker, two JVMs died silently in succession when running on Generational ZGC. Both deaths produced no `hs_err_*` crash dump, no Windows event log entry, and no log trace after the death point. ZGC was reverted; both apps now run JDK 21 + G1GC and remain stable.

**Timeline.**
- 21:56 — nqCore deployed on JDK 21 + ZGC (PID 5228). Healthy at 22:01 (curl returned 27 positions).
- 22:25–22:42 — nqTicker rebuilt on JDK 21, smoke-tested, deployed, restarted with `JDK_JAVA_OPTIONS=-XX:+UseZGC -XX:+ZGenerational` inline. All 6 processes verified on ZGC via `jcmd VM.flags` at 22:43.
- 22:43 — nqCore PID 5228 detected dead (curl returned HTTP 000). No crash dump.
- 22:49 — nqCore restarted (PID 9492) on ZGC. Healthy at 22:52.
- 22:55 — nqTicker PID 9956 + 5 children detected dead. No crash dump.
- 23:00 — `JDK_JAVA_OPTIONS` cleared at machine scope.
- 23:02 — nqCore restarted on G1GC (PID 10128). Stable.
- 23:03 — nqTicker started on G1GC (PID 3864 + 5 children). Stable.

**Root cause hypothesis: physical memory pressure on the 8 GB box.** Free RAM at the time of nqCore PID 5228's death was 2087 MB; with ZGC's higher per-JVM RSS overhead (~5-10% higher than G1GC, often 200+ MB extra at idle), the 7-JVM stack (nqCore + 6 nqTicker children) crossed the available physical memory threshold. JVMs aborted on metaspace mmap failure (the same failure mode recorded in `C:/Users/autotrading/Documents/amibroker-automator/hs_err_pid9532.log` from 2026-05-11: *"Failed to commit metaspace. Possible reasons: The system is out of physical RAM or swap space"*). The absence of `hs_err` files on the silent deaths is consistent with this failure mode — the hs_err writer itself can fail to allocate memory when metaspace is exhausted.

**RSS comparison at startup** (single-JVM):
- nqCore on ZGC: 509 MB
- nqCore on G1GC: 263 MB (-48%)
- 6-process nqTicker total on G1GC: 559 MB (was ~1.2-1.5 GB on ZGC, per memory delta observations)

**Permanent mitigation.** `JDK_JAVA_OPTIONS` env var cleared. Both apps stay on JDK 21 + G1GC (default). To re-enable ZGC in the future, the machine needs (a) more RAM, OR (b) explicit `-Xmx` caps on each JVM (suggest `-Xmx1g` for nqCore, `-Xmx512m` for each nqTicker child).

**What we kept.** JDK 21 itself (virtual threads, sequenced collections, generational improvements that are GC-agnostic), `spring.threads.virtual.enabled=true`, and the recompiled nqTicker fat jar. ZGC is the only thing reverted.

**Lesson.** Pre-flight memory headroom checks should accompany GC changes on memory-constrained hosts. A simple `Get-WmiObject Win32_OperatingSystem | Select FreePhysicalMemory` pre-check would have caught the risk before deploy.
