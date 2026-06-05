package path.to._40c.nqCore.gateway;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.zerodhatech.models.Order;

import jakarta.annotation.PreDestroy;

/**
 * Mock implementation of KiteOrderStream for the `mock` Spring profile.
 *
 * No WebSocket — instead, MockKiteGateway calls scheduleSyntheticFill(orderId, ...)
 * after a successful placeOrder to inject a fill event after a configurable delay
 * (default 50ms, simulating real exchange-side fill latency).
 *
 * Test hooks:
 *  - setHealthy(boolean): force-toggle health for fallback-path tests.
 *  - setSyntheticFillDelayMs(long): tune the simulated fill latency.
 */
@Component
@Profile("mock")
public class MockKiteOrderStream implements KiteOrderStream {

    private static final Logger log = LoggerFactory.getLogger(MockKiteOrderStream.class);

    private final ConcurrentHashMap<String, CompletableFuture<Order>> awaiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> cachedEvents = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mock-order-stream");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private volatile long syntheticFillDelayMs = 50L;

    @Override
    public CompletableFuture<Order> awaitTerminal(String orderId) {
        if (orderId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("null orderId"));
        Order cached = cachedEvents.remove(orderId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return awaiters.computeIfAbsent(orderId, k -> new CompletableFuture<>());
    }

    @Override
    public void cancel(String orderId) {
        if (orderId == null) return;
        awaiters.remove(orderId);
        cachedEvents.remove(orderId);
    }

    @Override
    public boolean isHealthy() {
        return healthy.get();
    }

    // --- Test / mock hooks (called from MockKiteGateway.placeOrder) ---

    /** Schedule a synthetic fill event to fire after syntheticFillDelayMs. */
    public void scheduleSyntheticFill(String orderId, String tradingSymbol, String txn, int qty, double avgPrice) {
        if (orderId == null || !healthy.get()) return;
        scheduler.schedule(() -> deliver(orderId, tradingSymbol, txn, qty, avgPrice),
                syntheticFillDelayMs, TimeUnit.MILLISECONDS);
    }

    public void setHealthy(boolean h) { healthy.set(h); }
    public void setSyntheticFillDelayMs(long ms) { this.syntheticFillDelayMs = ms; }

    private void deliver(String orderId, String tradingSymbol, String txn, int qty, double avgPrice) {
        Order o = new Order();
        o.orderId = orderId;
        o.tradingSymbol = tradingSymbol;
        o.transactionType = txn;
        o.status = "COMPLETE";
        o.filledQuantity = Integer.toString(qty);
        o.quantity = Integer.toString(qty);
        o.averagePrice = Double.toString(avgPrice);
        CompletableFuture<Order> awaiter = awaiters.remove(orderId);
        if (awaiter != null) {
            awaiter.complete(o);
        } else {
            cachedEvents.put(orderId, o);
        }
        log.debug("[MOCK] synthetic fill delivered: orderId={} symbol={} qty={} avg={}",
                orderId, tradingSymbol, qty, avgPrice);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        awaiters.values().forEach(f -> f.cancel(false));
        awaiters.clear();
        cachedEvents.clear();
    }
}
