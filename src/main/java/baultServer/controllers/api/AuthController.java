package baultServer.controllers.api;

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
import baultServer.model.EmailCode;
import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.exceptions.DeviceRemovedException;
import baultServer.exceptions.EmailDeliveryException;
import baultServer.services.DeviceService;
import baultServer.services.DeviceService.Registered;
import baultServer.services.EmailCodeService;
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
    private final EmailCodeService emailCodeService;

    public AuthController(AuthenticationManager authManager,
                          UserDetailsService userDetailsService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenService refreshTokenService,
                          DeviceService deviceService,
                          EmailCodeService emailCodeService) {
        this.authManager = authManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.deviceService = deviceService;
        this.emailCodeService = emailCodeService;
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
        Device device = null;
        String rawSecretToReturn = null;
        String operatingSystem = optionalText(body, "operatingSystem");
        String appVersion = optionalText(body, "appVersion");
        if (deviceId != null && deviceSecret != null) {
            try {
                device = deviceService.verify(user, deviceId, deviceSecret);
                device = deviceService.touchDeviceInfo(device, operatingSystem, appVersion);
            } catch (DeviceRemovedException e) {
                // El device viejo fue eliminado -> creamos uno nuevo transparentemente
                device = null;
            }
        }
        if (device == null) {
            Registered reg = deviceService.register(
                    user,
                    optionalText(body, "alias"),
                    operatingSystem,
                    appVersion);
            device = reg.device();
            rawSecretToReturn = reg.rawSecret();
        }

        String access = jwtService.generateToken(principal, device.getId());
        String refresh = refreshTokenService.issue(user, device);
        return ResponseEntity.ok(bearer(access, refresh, device.getId(), rawSecretToReturn, user.isEmailVerified()));
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
        u.setEmailVerified(false);
        u.setCreatedAt(ZonedDateTime.now());
        userRepository.save(u);

        Registered reg = deviceService.register(
                u,
                optionalText(body, "alias"),
                optionalText(body, "operatingSystem"),
                optionalText(body, "appVersion"));

        //Dispara el envío del código de verificación. Un fallo de entrega no rompe el registro:
        //el cliente puede pedir un reenvío desde /email/verify/request tras hacer login.
        try {
            emailCodeService.issueAndSend(u, EmailCode.Purpose.EMAIL_VERIFICATION);
        } catch (EmailDeliveryException ignored) {
            //Log ya emitido por el service; el user existe y puede reintentar.
        }

        UserDetails principal = userDetailsService.loadUserByUsername(email);
        String access = jwtService.generateToken(principal, reg.device().getId());
        String refresh = refreshTokenService.issue(u, reg.device());
        return ResponseEntity.ok(bearer(access, refresh, reg.device().getId(), reg.rawSecret(), u.isEmailVerified()));
    }

    @PostMapping("/email/verify/request")
    public ResponseEntity<Void> requestEmailVerification(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (user.isEmailVerified()) {
            return ResponseEntity.noContent().build();
        }
        emailCodeService.issueAndSend(user, EmailCode.Purpose.EMAIL_VERIFICATION);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify/confirm")
    public ResponseEntity<Void> confirmEmailVerification(@AuthenticationPrincipal UserDetails principal,
                                                         @RequestBody JsonNode body) {
        if (principal == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String code = requireText(body, "code");
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (user.isEmailVerified()) {
            return ResponseEntity.noContent().build();
        }
        emailCodeService.verifyAndConsume(user, EmailCode.Purpose.EMAIL_VERIFICATION, code);
        user.setEmailVerified(true);
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/reset/request")
    public ResponseEntity<Void> requestPasswordReset(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        //Respuesta constante para no filtrar existencia de la cuenta.
        userRepository.findByEmail(email).ifPresent(user -> {
            try {
                emailCodeService.issueAndSend(user, EmailCode.Purpose.PASSWORD_RESET);
            } catch (EmailDeliveryException ignored) {
                //Silencioso a propósito: mismo comportamiento visible que si el email no existe.
            }
        });
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        String code = requireText(body, "code");
        String newPassword = requireText(body, "newPassword");
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Invalid code"));
        emailCodeService.verifyAndConsume(user, EmailCode.Purpose.PASSWORD_RESET, code);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        //Invalida todas las sesiones tras cambio de contraseña.
        refreshTokenService.revokeAllForUser(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ObjectNode> refresh(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        Rotated rotated = refreshTokenService.rotate(refreshToken);
        deviceService.touchDeviceInfo(rotated.device(),
                optionalText(body, "operatingSystem"),
                optionalText(body, "appVersion"));
        UserDetails principal = userDetailsService.loadUserByUsername(rotated.user().getEmail());
        String access = jwtService.generateToken(principal, rotated.device().getId());
        return ResponseEntity.ok(bearer(access, rotated.rawToken(), rotated.device().getId(),
                null, rotated.user().isEmailVerified()));
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

    private static ObjectNode bearer(String accessToken, String refreshToken, Long deviceId,
                                     String deviceSecret, boolean emailVerified) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("accessToken", accessToken);
        node.put("refreshToken", refreshToken);
        node.put("tokenType", "Bearer");
        node.put("deviceId", deviceId);
        node.put("emailVerified", emailVerified);
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
