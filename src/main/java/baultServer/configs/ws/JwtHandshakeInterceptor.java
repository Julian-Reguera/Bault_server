package baultServer.configs.ws;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import baultServer.repositorys.UserRepository;
import baultServer.services.JwtService;
import io.jsonwebtoken.JwtException;

@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String DEVICE_ID_ATTR = "deviceId";
    public static final String USER_ID_ATTR = "userId";
    public static final String USER_EMAIL_ATTR = "userEmail";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtHandshakeInterceptor(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = header.substring(7);
        Long deviceId;
        String email;
        try {
            deviceId = jwtService.extractDeviceId(token);
            email = jwtService.extractUsername(token);
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (deviceId == null || email == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        //Resolvemos userId aquí (1 query) y lo guardamos en attributes. Así el HandshakeHandler
        //puede construir un Principal cuyo getName() sea el userId sin depender del SecurityContext,
        //y el fan-out de eventos usa /user/{userId}/queue/**.
        Long userId = userRepository.findByEmail(email).map(u -> u.getId()).orElse(null);
        if (userId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(DEVICE_ID_ATTR, deviceId);
        attributes.put(USER_ID_ATTR, userId);
        attributes.put(USER_EMAIL_ATTR, email);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
