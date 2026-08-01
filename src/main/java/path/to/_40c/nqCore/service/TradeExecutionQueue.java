package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.IST_FORMATTER;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes every trade-mutating operation (signal opens/closes/flips, orphan sweeps,
 * rollover, recenter, nqTicker callbacks) through one FIFO worker thread. AmiBroker fires
 * all strategies on the same bar close, so near-simultaneous signals are the norm once
 * multiple strategies are live; executing them strictly one at a time makes strike-occupancy
 * checks and SQLite writes race-free and implicitly stays inside Kite's rate limits.
 *
 * Producers (HTTP threads) only validate and enqueue — nothing outside this queue may place
 * orders or mutate the position book. A task must never submit into the queue and join the
 * result: the single worker thread would deadlock on itself. Intra-task parallelism on other
 * executors (flip's prepareOpen on the common pool, leg fan-out on LEG_EXEC, post-trade work
 * on postTradeExecutor) is fine and unaffected.
 */
@Service
@Slf4j
public class TradeExecutionQueue {

    /** Queue-wait above this logs a loud warning — the early signal that a task is jamming the pipeline. */
    private static final long QUEUE_WAIT_WARN_MS = 30_000;

    /** How many completed tasks the status snapshot remembers. */
    private static final int RECENT_LIMIT = 20;

    /** How long shutdown waits for queued trades to finish before forcing termination. */
    private static final long SHUTDOWN_DRAIN_SECONDS = 60;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "trade-exec"));
    private final AtomicInteger depth = new AtomicInteger();
    private final AtomicReference<RunningTask> current = new AtomicReference<>();
    private final ConcurrentLinkedDeque<CompletedTask> recent = new ConcurrentLinkedDeque<>();

    private record RunningTask(String label, Instant enqueuedAt, Instant startedAt) {}

    private record CompletedTask(String label, long waitedMs, long execMs, String outcome, Instant finishedAt) {}

    /**
     * Enqueues a trade-mutating action and returns immediately. The action runs on the single
     * worker thread in strict submission order. The action's boolean is the business outcome
     * (false = validly rejected, e.g. invalid sequence); an exception completes the future
     * exceptionally but never kills the worker or blocks later tasks. Callers on the HTTP
     * path ignore the future; tests and internal callers may join it.
     */
    public CompletableFuture<Boolean> submit(String label, Supplier<Boolean> action) {
        Instant enqueuedAt = Instant.now();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        depth.incrementAndGet();
        try {
            worker.execute(() -> run(label, enqueuedAt, action, result));
            log.info("QUEUED | task={} | depth={}", label, depth.get());
        } catch (RejectedExecutionException e) {
            depth.decrementAndGet();
            log.error("QUEUE REJECTED (shutdown in progress) | task={}", label);
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Worker-thread body. All accounting (depth, current, recent) is published BEFORE the
     * future completes, so a caller that joins the future observes a fully consistent
     * status snapshot — the drain-barrier pattern tests rely on.
     */
    private void run(String label, Instant enqueuedAt, Supplier<Boolean> action, CompletableFuture<Boolean> result) {
        Instant startedAt = Instant.now();
        long waitedMs = Duration.between(enqueuedAt, startedAt).toMillis();
        if (waitedMs > QUEUE_WAIT_WARN_MS) {
            log.warn("QUEUE DELAY | task={} waited {}ms behind earlier tasks — check for a hung broker call", label, waitedMs);
        }
        current.set(new RunningTask(label, enqueuedAt, startedAt));
        boolean ok = false;
        Throwable error = null;
        try {
            ok = Boolean.TRUE.equals(action.get());
        } catch (Throwable t) {
            error = t;
            log.error("QUEUE TASK FAILED | task={} — queue continues with the next task", label, t);
        }
        long execMs = Duration.between(startedAt, Instant.now()).toMillis();
        String outcome = error != null ? "ERROR: " + error.getMessage() : (ok ? "SUCCESS" : "REJECTED");
        current.set(null);
        depth.decrementAndGet();
        recent.addFirst(new CompletedTask(label, waitedMs, execMs, outcome, Instant.now()));
        while (recent.size() > RECENT_LIMIT) {
            recent.pollLast();
        }
        log.info("QUEUE DONE | task={} | outcome={} | waited={}ms | exec={}ms | depth={}",
                label, outcome, waitedMs, execMs, depth.get());
        if (error != null) {
            result.completeExceptionally(error);
        } else {
            result.complete(ok);
        }
    }

    /**
     * Point-in-time snapshot for GET /api/queue/status: depth (queued + running), the currently
     * executing task with wait/run times, and the last RECENT_LIMIT completions newest-first.
     */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("depth", depth.get());
        RunningTask rt = current.get();
        if (rt != null) {
            Map<String, Object> cur = new LinkedHashMap<>();
            cur.put("label", rt.label());
            cur.put("waitedMs", Duration.between(rt.enqueuedAt(), rt.startedAt()).toMillis());
            cur.put("runningMs", Duration.between(rt.startedAt(), Instant.now()).toMillis());
            s.put("current", cur);
        } else {
            s.put("current", null);
        }
        List<Map<String, Object>> completed = new ArrayList<>();
        for (CompletedTask c : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", c.label());
            m.put("outcome", c.outcome());
            m.put("waitedMs", c.waitedMs());
            m.put("execMs", c.execMs());
            m.put("finishedAt", IST_FORMATTER.format(c.finishedAt()));
            completed.add(m);
        }
        s.put("recent", completed);
        return s;
    }

    /**
     * Graceful shutdown: stop accepting new tasks, let queued trades finish (an in-flight
     * order placement must never be cut mid-execution), then force-stop if the drain window
     * is exceeded.
     */
    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.error("Trade queue did not drain within {}s — forcing shutdown, {} task(s) abandoned",
                        SHUTDOWN_DRAIN_SECONDS, depth.get());
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}
