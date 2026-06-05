package path.to._40c.nqCore.gateway;

import org.springframework.context.ApplicationEvent;

/**
 * Published by KiteAuthService.saveKiteAuth after a new access token is persisted
 * and the gateway cache is invalidated. Components holding stateful Kite-token-bound
 * resources (e.g. KiteOrderStream's WebSocket connection) listen for this and rebuild
 * with the fresh credentials.
 */
public class KiteAuthChangedEvent extends ApplicationEvent {

    public KiteAuthChangedEvent(Object source) {
        super(source);
    }
}
