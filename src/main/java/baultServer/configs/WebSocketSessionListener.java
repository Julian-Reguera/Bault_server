package baultServer.configs;

import java.security.Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketSessionListener {

    private static final Logger log = LogManager.getLogger(WebSocketSessionListener.class);

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WS connected — session={}, user={}",
                accessor.getSessionId(), nameOf(event.getUser()));
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        log.info("WS disconnected — session={}, user={}, status={}",
                event.getSessionId(), nameOf(event.getUser()), event.getCloseStatus());
    }

    private String nameOf(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
