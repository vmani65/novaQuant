# nqCore — Deferred Upgrade Plan

> **Purpose.** This is a self-contained execution plan for upgrading nqCore's runtime (Java 17 → 21) and its Spring Boot framework (3.5.13 → 4.0.6). It is written so a fresh AI agent or developer can execute each phase end-to-end without breaking core trading logic. Each phase is independent and can be deployed in isolation.
>
> **Last updated.** 2026-06-01. The Spring Boot 4.0.6 jar was already built + mock-validated (34/34 tests PASS) on this date; the pom.xml change was reverted to keep current prod stable while the freshly deployed graduated-LIMIT walk validates over a few trading days.

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
| `src/main/java/path/to/_40c/nqCore/gateway/KiteConnectGateway.java` | Real Kite wrapper — DO NOT touch |
| `src/main/java/path/to/_40c/nqCore/gateway/MockKiteGateway.java` | Mock used by `mock,test` profile — DO NOT touch |
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
| A | 2026-06-02 ~21:56 IST | (unstaged) | **DEPLOYED.** JDK 21.0.11 (Microsoft Build) + spring.threads.virtual.enabled=true. Mock suite 34/34 PASS pre-deploy. LIVE position 29 open at deploy time (operator-authorized override). Final config: **G1GC (NOT ZGC)** — ZGC was tried but rolled back after silent JVM deaths under memory pressure on the 8GB box. See incident note at bottom of this file. |
| B | — | — | Not started |
| C | — | — | Not started (mock-validated 2026-06-01, pom revert intentional) |
| nqTicker→21 | 2026-06-02 ~22:30 IST | (unstaged in nqTicker repo) | **DEPLOYED.** Separate task authorized by operator: nqTicker `maven.compiler.source/target` bumped 17→21, rebuilt as shaded fat jar, deployed to `C:/novaquant/nqTicker/nqTicker-2026.05.jar` (old jar preserved at `.bak.prePhaseA`). All 6 processes (parent + 5 children: gateway, sqlite, rollover, recenter, log-aggregator) verified on JDK 21 + G1GC. Position 29 detected via signals.db cross-process. Ports 9191/9195 listening; 9192 open-buffer exits off-hours by design. |

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
