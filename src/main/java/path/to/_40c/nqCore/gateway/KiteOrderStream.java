package path.to._40c.nqCore.gateway;

import java.util.concurrent.CompletableFuture;

import com.zerodhatech.models.Order;

/**
 * Push-based interface to Kite's order-update stream (a WebSocket in the real impl).
 * Lets the LIMIT walk in PositionUtil.placeGraduatedLimit replace a blind 500ms sleep
 * with an event-driven await — the fill is detected the instant it lands on the
 * exchange side, typically 30-80ms after placeOrder confirms.
 *
 * Contract:
 *  - awaitTerminal(orderId) returns a future that fires the next time the stream
 *    sees a terminal-or-meaningful state for that orderId (COMPLETE, REJECTED,
 *    CANCELLED — or partial fills with non-zero filledQuantity).
 *  - The impl MUST cache the latest event per orderId so awaiters registering
 *    after-the-fact still get the prior event (handles the race where placeOrder
 *    returns and we register the awaiter AFTER the fill event already fired).
 *  - cancel(orderId) drops the awaiter and any cached state. Call this from
 *    the MARKET-fallback path or when timing out.
 *  - isHealthy() reports stream connectivity. Callers fall back to legacy
 *    REST polling when false.
 */
public interface KiteOrderStream {

    /**
     * Register interest in the next meaningful state event for an order.
     * If an event for this orderId has already been received and is still cached
     * (no prior awaiter consumed it), returns an already-completed future.
     * Otherwise, returns a fresh pending future to be completed by the next event.
     *
     * Multiple concurrent callers for the same orderId share the same future.
     */
    CompletableFuture<Order> awaitTerminal(String orderId);

    /** Drop awaiter + cached event for this orderId. Safe to call multiple times. */
    void cancel(String orderId);

    /** True when the underlying transport is connected and receiving events. */
    boolean isHealthy();
}
