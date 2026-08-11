package baultServer.configs.ws;

import java.security.Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import baultServer.repositorys.DevicePresenceRepository;
import baultServer.services.EventWsBroadcaster;

@Component
public class WebSocketSessionListener {

    private static final Logger log = LogManager.getLogger(WebSocketSessionListener.class);

    private final DevicePresenceRepository presenceRepository;
    private final EventWsBroadcaster eventBroadcaster;

    public WebSocketSessionListener(DevicePresenceRepository presenceRepository,
                                    EventWsBroadcaster eventBroadcaster) {
        this.presenceRepository = presenceRepository;
        this.eventBroadcaster = eventBroadcaster;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        //El CONNECT sólo autentica; la marca "online" ocurre al SUBSCRIBE al canal RPC
        //(gestionado en StompAuthChannelInterceptor). Aquí sólo dejamos rastro.
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        log.info("WS connected — session={}, user={}",
                accessor.getSessionId(), nameOf(event.getUser()));
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        //Al cerrarse la sesión (unsubscribe, disconnect explícito o timeout de heartbeat)
        //liberamos el reclamo de presencia. Idempotente: si la sesión nunca reclamó
        //el canal RPC, release devuelve null y no ocurre nada.
        Long releasedDeviceId = presenceRepository.release(event.getSessionId());
        log.info("WS disconnected — session={}, user={}, status={}, releasedDevice={}",
                event.getSessionId(), nameOf(event.getUser()), event.getCloseStatus(), releasedDeviceId);

        //Notifica al resto de sesiones del mismo usuario. Solo se emite si la sesión
        //efectivamente había reclamado un device (release devolvió no-null).
        if (releasedDeviceId != null) {
            Long userId = parseUserId(nameOf(event.getUser()));
            if (userId != null) {
                eventBroadcaster.devicePresence(userId, releasedDeviceId, false);
            }
        }
    }

    private String nameOf(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }

    private static Long parseUserId(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
