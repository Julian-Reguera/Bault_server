package baultServer.configs.ws;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Determina el {@link Principal} de cada sesión WebSocket. Reemplaza el Principal
 * por defecto (Spring Security Authentication, cuyo {@code getName()} es el email)
 * por un {@link UserIdPrincipal} cuyo {@code getName()} es el userId como String.
 * <p>
 * Con esto, {@code SimpMessagingTemplate.convertAndSendToUser(String.valueOf(userId), ...)}
 * enruta a {@code /user/{userId}/queue/**} y llega a todas las sesiones del usuario.
 * <p>
 * El userId ya lo ha resuelto {@link JwtHandshakeInterceptor} y lo ha guardado en
 * los attributes de la sesión — aquí sólo lo leemos, sin acceder al SecurityContext.
 */
@Component
public class UserIdHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                       WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
        Object userId = attributes.get(JwtHandshakeInterceptor.USER_ID_ATTR);
        Object email = attributes.get(JwtHandshakeInterceptor.USER_EMAIL_ATTR);
        if (!(userId instanceof Long uid)) {
            return null;
        }
        return new UserIdPrincipal(uid, email instanceof String s ? s : null);
    }
}
