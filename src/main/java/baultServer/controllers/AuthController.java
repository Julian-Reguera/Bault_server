package baultServer.controllers;

import java.time.ZonedDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceService;
import baultServer.services.DeviceService.Registered;
import baultServer.services.JwtService;
import baultServer.services.RefreshTokenService;
import baultServer.services.RefreshTokenService.Rotated;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final DeviceService deviceService;

    public AuthController(AuthenticationManager authManager,
                          UserDetailsService userDetailsService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenService refreshTokenService,
                          DeviceService deviceService) {
        this.authManager = authManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.deviceService = deviceService;
    }

    @PostMapping("/login/password")
    public ResponseEntity<ObjectNode> login(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        String password = requireText(body, "password");
        Long deviceId = optionalLong(body, "deviceId");
        String deviceSecret = optionalText(body, "deviceSecret");

        //Autentifica con Spring Security (si fallan las credenciales lanza excepción)
        Authentication authResult = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        UserDetails principal = (UserDetails) authResult.getPrincipal();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));

        //Verificación de dispositivo
        Device device;
        String rawSecretToReturn = null;
        if (deviceId != null && deviceSecret != null) {
            device = deviceService.verify(user, deviceId, deviceSecret);
        } else {
            Registered reg = deviceService.register(
                    user,
                    optionalText(body, "alias"),
                    optionalText(body, "operatingSystem"),
                    optionalText(body, "appVersion"));
            device = reg.device();
            rawSecretToReturn = reg.rawSecret();
        }

        String access = jwtService.generateToken(principal, device.getId());
        String refresh = refreshTokenService.issue(user, device);
        return ResponseEntity.ok(bearer(access, refresh, device.getId(), rawSecretToReturn));
    }

    @PostMapping("/register/password")
    public ResponseEntity<ObjectNode> register(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        String password = requireText(body, "password");
        String firstName = optionalText(body, "firstName");
        String lastName = optionalText(body, "lastName");

        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(CONFLICT, "Email already registered");
        }

        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRoles("USER");
        u.setEnabled(true);
        u.setCreatedAt(ZonedDateTime.now());
        userRepository.save(u);

        Registered reg = deviceService.register(
                u,
                optionalText(body, "alias"),
                optionalText(body, "operatingSystem"),
                optionalText(body, "appVersion"));

        UserDetails principal = userDetailsService.loadUserByUsername(email);
        String access = jwtService.generateToken(principal, reg.device().getId());
        String refresh = refreshTokenService.issue(u, reg.device());
        return ResponseEntity.ok(bearer(access, refresh, reg.device().getId(), reg.rawSecret()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ObjectNode> refresh(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        Rotated rotated = refreshTokenService.rotate(refreshToken);
        UserDetails principal = userDetailsService.loadUserByUsername(rotated.user().getEmail());
        String access = jwtService.generateToken(principal, rotated.device().getId());
        return ResponseEntity.ok(bearer(access, rotated.rawToken(), rotated.device().getId(), null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        refreshTokenService.revokeAllForUser(user);
        return ResponseEntity.noContent().build();
    }

    private static ObjectNode bearer(String accessToken, String refreshToken, Long deviceId, String deviceSecret) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("accessToken", accessToken);
        node.put("refreshToken", refreshToken);
        node.put("tokenType", "Bearer");
        node.put("deviceId", deviceId);
        if (deviceSecret != null) {
            node.put("deviceSecret", deviceSecret);
        }
        return node;
    }

    private static String requireText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing or invalid field: " + field);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return (node == null || node.isNull() || !node.isTextual()) ? null : node.asText();
    }

    private static Long optionalLong(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
