package baultServer.configs.ws;

import java.security.Principal;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import baultServer.repositorys.DevicePresenceRepository;
import baultServer.services.EventWsBroadcaster;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String DEVICE_QUEUE_PREFIX = "/queue/device.";
    private static final String APP_DESTINATION_PREFIX = "/app/";

    private final DevicePresenceRepository presenceRepository;
    private final EventWsBroadcaster eventBroadcaster;

    //@Lazy rompe el ciclo:
    //  WebSocketConfig → StompAuthChannelInterceptor → EventWsBroadcaster
    //    → SimpMessagingTemplate (creado por la infra WS que a su vez depende de WebSocketConfig).
    //Spring inyecta un proxy y sólo materializa el bean real al primer uso, cuando el ciclo ya está resuelto.
    public StompAuthChannelInterceptor(DevicePresenceRepository presenceRepository,
                                       @Lazy EventWsBroadcaster eventBroadcaster) {
        this.presenceRepository = presenceRepository;
        this.eventBroadcaster = eventBroadcaster;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT -> requirePrincipal(accessor); //Usuario tiene que estar logueado
            case SUBSCRIBE -> authorizeSubscription(accessor); //Comprueba queue/device.{id} y reclama presencia
            case SEND -> authorizeSend(accessor); //Solo destinos /app/**
            default -> {}
        }
        return message;
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal principal = requirePrincipal(accessor);
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("Missing destination");
        }

        Long sessionDeviceId = deviceIdOf(accessor);
        if (sessionDeviceId == null) {
            throw new AccessDeniedException("Missing device in session");
        }

        if (destination.startsWith(DEVICE_QUEUE_PREFIX)) {
            String requested = destination.substring(DEVICE_QUEUE_PREFIX.length());
            if (!requested.equals(sessionDeviceId.toString())) {
                throw new AccessDeniedException(
                        "Device " + principal.getName() + " cannot subscribe to " + destination);
            }
            //Reclamo exclusivo: solo una sesión suscrita al canal RPC del device a la vez.
            //Si ya hay otra sesión reclamada, se rechaza; el heartbeat liberará la anterior
            //si estaba muerta y el cliente podrá reintentar.
            String sessionId = accessor.getSessionId();
            if (sessionId == null || !presenceRepository.tryClaim(sessionDeviceId, sessionId)) {
                throw new AccessDeniedException(
                        "Device " + sessionDeviceId + " already connected in another session");
            }
            //Notifica al resto de sesiones del mismo usuario del cambio de presencia.
            //principal.getName() = userId (ver UserIdPrincipal).
            Long userId = parseUserId(principal.getName());
            if (userId != null) {
                eventBroadcaster.devicePresence(userId, sessionDeviceId, true);
            }
        }
    }

    private static Long parseUserId(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        requirePrincipal(accessor);
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_DESTINATION_PREFIX)) {
            throw new AccessDeniedException(
                    "Clients may only SEND to " + APP_DESTINATION_PREFIX + "** destinations");
        }
    }

    private Principal requirePrincipal(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new AccessDeniedException("Unauthenticated STOMP frame");
        }
        return principal;
    }

    private Long deviceIdOf(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) return null;
        Object value = attrs.get(JwtHandshakeInterceptor.DEVICE_ID_ATTR);
        return (value instanceof Long l) ? l : null;
    }
}
