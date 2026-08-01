package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the serialization contract the multi-strategy build depends on: tasks run strictly
 * one at a time in submission order, a failing task never blocks the next one, accounting
 * (depth/current/recent) is consistent by the time a task's future completes, and shutdown
 * drains queued trades instead of abandoning them.
 */
class TradeExecutionQueueTest {

    private TradeExecutionQueue queue;

    @BeforeEach
    void setUp() {
        queue = new TradeExecutionQueue();
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    @DisplayName("tasks execute strictly one at a time, in submission order")
    void fifoAndNeverConcurrent() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        futures.add(queue.submit("t0", () -> {
            awaitQuietly(gate);
            return track(0, executionOrder, active, maxActive);
        }));
        for (int i = 1; i <= 9; i++) {
            int id = i;
            futures.add(queue.submit("t" + id, () -> track(id, executionOrder, active, maxActive)));
        }

        gate.countDown();
        for (CompletableFuture<Boolean> f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(executionOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(maxActive.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a throwing task completes its future exceptionally but the next task still runs")
    void failureIsolation() throws Exception {
        CompletableFuture<Boolean> boom = queue.submit("boom", () -> {
            throw new RuntimeException("kite exploded");
        });
        CompletableFuture<Boolean> next = queue.submit("next", () -> true);

        assertThatThrownBy(() -> boom.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("kite exploded");
        assertThat(next.get(5, TimeUnit.SECONDS)).isTrue();

        List<Map<String, Object>> recent = recentOf(queue.status());
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).get("label")).isEqualTo("next");
        assertThat(recent.get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(recent.get(1).get("label")).isEqualTo("boom");
        assertThat(recent.get(1).get("outcome").toString()).startsWith("ERROR");
    }

    @Test
    @DisplayName("a task returning false records a REJECTED outcome")
    void rejectedOutcome() throws Exception {
        CompletableFuture<Boolean> f = queue.submit("rejected-signal", () -> false);
        assertThat(f.get(5, TimeUnit.SECONDS)).isFalse();

        List<Map<String, Object>> recent = recentOf(queue.status());
        assertThat(recent.get(0).get("label")).isEqualTo("rejected-signal");
        assertThat(recent.get(0).get("outcome")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("status reflects depth and current task while running, and clears when idle")
    void statusSnapshot() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        queue.submit("running-task", () -> {
            started.countDown();
            awaitQuietly(release);
            return true;
        });
        CompletableFuture<Boolean> waiting = queue.submit("waiting-task", () -> true);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> busy = queue.status();
        assertThat(busy.get("depth")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) busy.get("current");
        assertThat(current).isNotNull();
        assertThat(current.get("label")).isEqualTo("running-task");

        release.countDown();
        waiting.get(5, TimeUnit.SECONDS);
        Map<String, Object> idle = queue.status();
        assertThat(idle.get("depth")).isEqualTo(0);
        assertThat(idle.get("current")).isNull();
        assertThat(recentOf(idle)).hasSize(2);
    }

    @Test
    @DisplayName("submit after shutdown is rejected, not silently dropped")
    void submitAfterShutdownRejected() {
        queue.shutdown();
        CompletableFuture<Boolean> late = queue.submit("late", () -> true);
        assertThatThrownBy(() -> late.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(queue.status().get("depth")).isEqualTo(0);
    }

    @Test
    @DisplayName("shutdown drains queued tasks before terminating")
    void shutdownDrainsPending() {
        AtomicBoolean secondRan = new AtomicBoolean(false);
        queue.submit("slow", () -> {
            sleepQuietly(200);
            return true;
        });
        queue.submit("second", () -> {
            secondRan.set(true);
            return true;
        });
        queue.shutdown();
        assertThat(secondRan).isTrue();
    }

    private static boolean track(int id, List<Integer> order, AtomicInteger active, AtomicInteger maxActive) {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        sleepQuietly(5);
        order.add(id);
        active.decrementAndGet();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentOf(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("recent");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
