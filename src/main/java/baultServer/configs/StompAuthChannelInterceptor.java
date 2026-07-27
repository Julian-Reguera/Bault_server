package baultServer.configs;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import baultServer.model.Device;
import baultServer.repositorys.DeviceRepository;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String DEVICE_QUEUE_PREFIX = "/queue/device.";
    private static final String APP_DESTINATION_PREFIX = "/app/";

    private final DeviceRepository deviceRepository;

    public StompAuthChannelInterceptor(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
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
            case SUBSCRIBE -> authorizeSubscription(accessor); //Comprueba queue/device.{id}
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

        Device device = deviceRepository.findById(sessionDeviceId).orElse(null);
        if (device == null || !device.isEnabled()) {
            throw new AccessDeniedException(
                    "Device " + sessionDeviceId + " is disabled");
        }

        if (destination.startsWith(DEVICE_QUEUE_PREFIX)) {
            String requested = destination.substring(DEVICE_QUEUE_PREFIX.length());
            if (!requested.equals(sessionDeviceId.toString())) {
                throw new AccessDeniedException(
                        "Device " + principal.getName() + " cannot subscribe to " + destination);
            }
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
