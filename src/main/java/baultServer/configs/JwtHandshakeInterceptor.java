package baultServer.configs;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import baultServer.model.Device;
import baultServer.repositorys.DeviceRepository;
import baultServer.services.JwtService;
import io.jsonwebtoken.JwtException;

@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    static final String DEVICE_ID_ATTR = "deviceId";

    private final JwtService jwtService;
    private final DeviceRepository deviceRepository;

    public JwtHandshakeInterceptor(JwtService jwtService, DeviceRepository deviceRepository) {
        this.jwtService = jwtService;
        this.deviceRepository = deviceRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Long deviceId;
        try {
            deviceId = jwtService.extractDeviceId(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (deviceId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || !device.isEnabled()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(DEVICE_ID_ATTR, deviceId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
