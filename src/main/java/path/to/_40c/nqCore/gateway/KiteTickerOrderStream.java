package path.to._40c.nqCore.gateway;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnError;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import path.to._40c.nqCore.entity.KiteAuthDetails;
import path.to._40c.nqCore.repo.KiteAuthDetailsRepository;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

/**
 * Production implementation of KiteOrderStream using Kite Connect's KiteTicker WebSocket.
 * One process-wide connection multiplexes order updates for all in-flight orders.
 *
 * Lifecycle:
 *  - @PostConstruct: attempts initial connect using today's auth row. If no auth row
 *    exists (e.g. fresh restart before user has authed), construction succeeds with
 *    healthy=false; caller falls back to legacy REST polling.
 *  - On KiteAuthChangedEvent: tears down existing ticker (if any) and reconnects with
 *    fresh access token. Triggered after the operator re-auths via /saveKiteAuth.
 *  - @PreDestroy: gracefully disconnects on JVM shutdown.
 *
 * Event filtering: we propagate only events where filledQuantity > 0 OR status is
 * terminal. This avoids spurious "OPEN" events with 0 filled triggering caller logic
 * unnecessarily — the caller wants to know about meaningful state changes only.
 */
@Component
@Profile("live")
public class KiteTickerOrderStream implements KiteOrderStream {

    private static final Logger log = LoggerFactory.getLogger(KiteTickerOrderStream.class);

    private static final String STATUS_COMPLETE  = "COMPLETE";
    private static final String STATUS_REJECTED  = "REJECTED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final String apiKey;
    private final KiteAuthDetailsRepository authRepo;

    private volatile KiteTicker ticker;
    private volatile boolean healthy = false;
    private volatile boolean shutdownRequested = false;

    /** Per-orderId pending awaiter (single shared future for all concurrent callers). */
    private final ConcurrentHashMap<String, CompletableFuture<Order>> awaiters = new ConcurrentHashMap<>();
    /** Per-orderId most-recent unmatched event — handles the place→register race. */
    private final ConcurrentHashMap<String, Order> cachedEvents = new ConcurrentHashMap<>();

    /** Drives the disconnect → reconnect loop. Daemon, single-thread, sized for occasional retries. */
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });
    /** Reconnect attempts since last successful connect — drives the backoff curve, reset on connect. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public KiteTickerOrderStream(
            @Value("${kite.api-key}") String apiKey,
            KiteAuthDetailsRepository authRepo) {
        this.apiKey = apiKey;
        this.authRepo = authRepo;
    }

    @PostConstruct
    public void connect() {
        tryConnect();
    }

    @PreDestroy
    public void shutdown() {
        shutdownRequested = true;
        reconnectScheduler.shutdownNow();
        disconnectQuietly();
    }

    @EventListener(KiteAuthChangedEvent.class)
    public void onAuthChanged(KiteAuthChangedEvent e) {
        log.info("KiteOrderStream: auth changed — reconnecting WS with fresh token");
        consecutiveFailures.set(0);
        disconnectQuietly();
        tryConnect();
    }

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
        return healthy && ticker != null && ticker.isConnectionOpen();
    }

    private void tryConnect() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        Optional<KiteAuthDetails> existing = authRepo.findByAuthDate(today);
        if (existing.isEmpty()) {
            log.warn("KiteOrderStream: no auth row for today — WS connect deferred; legacy REST path will be used until /saveKiteAuth fires");
            healthy = false;
            return;
        }
        try {
            KiteTicker t = new KiteTicker(existing.get().getAccessToken(), apiKey);
            t.setOnOrderUpdateListener(this::onOrderUpdate);
            t.setOnConnectedListener(() -> {
                healthy = true;
                consecutiveFailures.set(0);
                log.info("KiteOrderStream: WS connected");
            });
            t.setOnDisconnectedListener(() -> {
                healthy = false;
                log.warn("KiteOrderStream: WS disconnected — legacy REST path will engage");
                scheduleReconnect();
            });
            t.setOnErrorListener(new OnError() {
                @Override public void onError(Exception e)       { healthy = false; log.warn("KiteOrderStream: WS error (Exception)", e); }
                @Override public void onError(KiteException ke)  { healthy = false; log.warn("KiteOrderStream: WS error (KiteException) code={} message={}", ke.code, ke.getMessage()); }
                @Override public void onError(String s)          { healthy = false; log.warn("KiteOrderStream: WS error (String): {}", s); }
            });
            t.setTryReconnection(true);
            try { t.setMaximumRetries(10); } catch (KiteException ignored) {}
            try { t.setMaximumRetryInterval(30); } catch (KiteException ignored) {}
            t.connect();
            this.ticker = t;
        } catch (Exception e) {
            healthy = false;
            log.error("KiteOrderStream: connect failed — staying on legacy REST path", e);
        }
    }

    private void disconnectQuietly() {
        try {
            if (ticker != null && ticker.isConnectionOpen()) ticker.disconnect();
        } catch (Exception ignored) {}
        ticker = null;
        healthy = false;
        awaiters.values().forEach(f -> f.cancel(false));
        awaiters.clear();
        cachedEvents.clear();
    }

    /**
     * Schedule an active reconnect attempt with exponential backoff.
     * Called from the OnDisconnect listener — we don't rely solely on KiteTicker's
     * internal setTryReconnection(true) because its retry budget is exhaustible and
     * once exceeded the ticker stays dead until our process intervenes.
     *
     * Backoff: 5s, 10s, 20s, 40s, then capped at 60s. Reset to 0 on successful connect.
     */
    private void scheduleReconnect() {
        if (shutdownRequested) return;
        int n = consecutiveFailures.incrementAndGet();
        long delayMs = Math.min(60_000L, 5_000L * (1L << Math.min(n - 1, 4)));
        log.info("KiteOrderStream: scheduling reconnect attempt {} in {}ms", n, delayMs);
        try {
            reconnectScheduler.schedule(this::attemptReconnect, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // scheduler shut down between the check above and here — process is dying, ignore
        }
    }

    /** Runs on the reconnect-scheduler thread. Tears down any half-dead ticker and rebuilds. */
    private void attemptReconnect() {
        if (shutdownRequested || healthy) return;
        log.info("KiteOrderStream: reconnect attempt firing now (failure #{})", consecutiveFailures.get());
        try {
            disconnectQuietly();
            tryConnect();
        } catch (Exception e) {
            log.warn("KiteOrderStream: reconnect attempt threw — will retry: {}", e.getMessage());
        }
        // ticker.connect() is asynchronous — followup check confirms whether OnConnect fired
        try {
            reconnectScheduler.schedule(() -> {
                if (shutdownRequested) return;
                if (healthy) {
                    log.info("KiteOrderStream: reconnect succeeded; backoff counter reset");
                    consecutiveFailures.set(0);
                } else {
                    log.warn("KiteOrderStream: reconnect attempt did not become healthy — scheduling another");
                    scheduleReconnect();
                }
            }, 5_000L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /** Internal: KiteTicker OnOrderUpdate callback — runs on the SDK's WS thread. */
    private void onOrderUpdate(Order o) {
        if (o == null || o.orderId == null) return;
        if (!isMeaningful(o)) return;
        CompletableFuture<Order> awaiter = awaiters.remove(o.orderId);
        if (awaiter != null) {
            awaiter.complete(o);
        } else {
            cachedEvents.put(o.orderId, o);
        }
    }

    /** Filter: filledQuantity > 0 OR terminal status. Avoids spurious OPEN events. */
    private static boolean isMeaningful(Order o) {
        if (isTerminal(o.status)) return true;
        try {
            return o.filledQuantity != null && !o.filledQuantity.isBlank()
                && Integer.parseInt(o.filledQuantity.trim()) > 0;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }

    private static boolean isTerminal(String status) {
        return STATUS_COMPLETE.equals(status)
            || STATUS_REJECTED.equals(status)
            || STATUS_CANCELLED.equals(status);
    }
}
