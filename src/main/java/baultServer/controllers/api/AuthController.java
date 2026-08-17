package baultServer.controllers.api;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiErrorResponse;
import baultServer.exceptions.ApiException;
import baultServer.exceptions.DeviceAuthenticationException;
import baultServer.exceptions.DeviceBlockedException;
import baultServer.exceptions.DeviceRemovedException;
import baultServer.exceptions.EmailCodeCooldownException;
import baultServer.exceptions.EmailCodeExpiredException;
import baultServer.exceptions.EmailCodeInvalidException;
import baultServer.exceptions.EmailCodeNotFoundException;
import baultServer.exceptions.EmailDeliveryException;
import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.EmailCode;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceService;
import baultServer.services.DeviceService.Registered;
import baultServer.services.EmailCodeService;
import baultServer.services.JwtService;
import baultServer.services.RefreshTokenService;
import baultServer.services.RefreshTokenService.Rotated;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    //RFC 5322 simplificada: caracteres locales comunes + dominio con al menos un punto y TLD de 2+ letras.
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final int EMAIL_MAX_LENGTH = 254;

    private final AuthenticationManager authManager;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final DeviceService deviceService;
    private final EmailCodeService emailCodeService;
    private final String defaultPlanName;

    public AuthController(AuthenticationManager authManager,
                          UserDetailsService userDetailsService,
                          UserRepository userRepository,
                          BillingPlanRepository billingPlanRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenService refreshTokenService,
                          DeviceService deviceService,
                          EmailCodeService emailCodeService,
                          @Value("${bault.plan.default:FREE}") String defaultPlanName) {
        this.authManager = authManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.billingPlanRepository = billingPlanRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.deviceService = deviceService;
        this.emailCodeService = emailCodeService;
        this.defaultPlanName = defaultPlanName;
    }

    @PostMapping("/public/login/password")
    public ResponseEntity<?> login(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        String password = requireText(body, "password");
        Long deviceId = optionalLong(body, "deviceId");
        String deviceSecret = optionalText(body, "deviceSecret");
        String operatingSystem = optionalText(body, "operatingSystem");
        String appVersion = optionalText(body, "appVersion");

        //Autentifica con Spring Security. Credenciales inválidas -> BadCredentialsException,
        //cuenta deshabilitada -> DisabledException. Ambas se traducen en el advice.
        Authentication authResult = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        UserDetails principal = (UserDetails) authResult.getPrincipal();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ApiErrorCode.USER_NOT_FOUND));

        //Bloquea el login si el email no está verificado. Código distinto para que el cliente
        //pueda diferenciarlo de credenciales incorrectas y disparar el flujo de verificación.
        if (!user.isEmailVerified()) {
            ApiErrorResponse body403 = ApiErrorResponse.of(
                    ApiErrorCode.EMAIL_NOT_VERIFIED,
                    ApiErrorCode.EMAIL_NOT_VERIFIED.defaultMessage(),
                    Map.of("email", user.getEmail()));
            return ResponseEntity.status(ApiErrorCode.EMAIL_NOT_VERIFIED.status()).body(body403);
        }

        //Verificación de dispositivo
        Device device = null;
        String rawSecretToReturn = null;
        if (deviceId != null && deviceSecret != null) {
            try {
                device = deviceService.verify(user, deviceId, deviceSecret);
                device = deviceService.touchDeviceInfo(device, operatingSystem, appVersion);
            } catch (DeviceRemovedException e) {
                // El device viejo fue eliminado -> creamos uno nuevo transparentemente
                device = null;
            } catch (DeviceBlockedException e) {
                throw new ApiException(ApiErrorCode.DEVICE_BLOCKED);
            } catch (DeviceAuthenticationException e) {
                throw new ApiException(ApiErrorCode.DEVICE_INVALID_SECRET, e.getMessage());
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
        return ResponseEntity.ok(bearer(access, refresh, device.getId(), rawSecretToReturn));
    }

    @PostMapping("/public/register/password")
    public ResponseEntity<ObjectNode> register(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        String password = requireText(body, "password");
        String firstName = optionalText(body, "firstName");
        String lastName = optionalText(body, "lastName");

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ApiErrorCode.EMAIL_ALREADY_REGISTERED);
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
        //Sin plan por defecto, el user no puede registrar devices ni hacer transferencias (403).
        BillingPlan defaultPlan = billingPlanRepository.findByName(defaultPlanName).orElse(null);
        if (defaultPlan == null) {
            log.warn("Default billing plan '{}' not found; user {} will be created without plan",
                    defaultPlanName, email);
        } else {
            u.setBillingPlan(defaultPlan);
            u.setPlanSubscribedAt(u.getCreatedAt());
        }
        userRepository.save(u);

        //No emitimos tokens ni creamos device: el login exige email verificado, así que serían inservibles.
        //El device se creará en el primer login válido, tras la verificación.
        try {
            emailCodeService.issueAndSend(u, EmailCode.Purpose.EMAIL_VERIFICATION);
        } catch (EmailDeliveryException ignored) {
            //Log ya emitido por el service; el cliente puede reintentar con /public/email/verify/request.
        } catch (EmailCodeCooldownException e) {
            throw cooldown(e);
        }

        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("email", u.getEmail());
        resp.put("message", "verification_email_sent");
        return ResponseEntity.accepted().body(resp);
    }

    @PostMapping("/public/email/verify/request")
    public ResponseEntity<Void> requestEmailVerification(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        //Respuesta constante para no filtrar existencia de la cuenta ni estado de verificación.
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                try {
                    emailCodeService.issueAndSend(user, EmailCode.Purpose.EMAIL_VERIFICATION);
                } catch (EmailDeliveryException ignored) {
                    //Silencioso a propósito: mismo comportamiento visible que si el email no existe.
                } catch (EmailCodeCooldownException e) {
                    throw cooldown(e);
                }
            }
        });
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/public/email/verify/confirm")
    public ResponseEntity<Void> confirmEmailVerification(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        String code = requireText(body, "code");
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ApiErrorCode.EMAIL_CODE_INVALID));
        if (user.isEmailVerified()) {
            return ResponseEntity.noContent().build();
        }
        try {
            emailCodeService.verifyAndConsume(user, EmailCode.Purpose.EMAIL_VERIFICATION, code);
        } catch (EmailCodeNotFoundException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_NOT_FOUND);
        } catch (EmailCodeInvalidException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_INVALID);
        } catch (EmailCodeExpiredException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_EXPIRED);
        }
        user.setEmailVerified(true);
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/public/password/reset/request")
    public ResponseEntity<Void> requestPasswordReset(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        //Respuesta constante para no filtrar existencia de la cuenta.
        userRepository.findByEmail(email).ifPresent(user -> {
            try {
                emailCodeService.issueAndSend(user, EmailCode.Purpose.PASSWORD_RESET);
            } catch (EmailDeliveryException ignored) {
                //Silencioso a propósito: mismo comportamiento visible que si el email no existe.
            } catch (EmailCodeCooldownException e) {
                throw cooldown(e);
            }
        });
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/public/password/reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody JsonNode body) {
        String email = requireEmail(body, "email");
        String code = requireText(body, "code");
        String newPassword = requireText(body, "newPassword");
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ApiErrorCode.EMAIL_CODE_INVALID));
        try {
            emailCodeService.verifyAndConsume(user, EmailCode.Purpose.PASSWORD_RESET, code);
        } catch (EmailCodeNotFoundException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_NOT_FOUND);
        } catch (EmailCodeInvalidException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_INVALID);
        } catch (EmailCodeExpiredException e) {
            throw new ApiException(ApiErrorCode.EMAIL_CODE_EXPIRED);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        //Invalida todas las sesiones tras cambio de contraseña.
        refreshTokenService.revokeAllForUser(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/public/refresh")
    public ResponseEntity<ObjectNode> refresh(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        Rotated rotated = refreshTokenService.rotate(refreshToken);
        deviceService.touchDeviceInfo(rotated.device(),
                optionalText(body, "operatingSystem"),
                optionalText(body, "appVersion"));
        UserDetails principal = userDetailsService.loadUserByUsername(rotated.user().getEmail());
        String access = jwtService.generateToken(principal, rotated.device().getId());
        return ResponseEntity.ok(bearer(access, rotated.rawToken(), rotated.device().getId(), null));
    }

    @PostMapping("/public/logout")
    public ResponseEntity<Void> logout(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/secured/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ApiException(ApiErrorCode.USER_NOT_FOUND));
        refreshTokenService.revokeAllForUser(user);
        return ResponseEntity.noContent().build();
    }

    private static ObjectNode bearer(String accessToken, String refreshToken, Long deviceId,
                                     String deviceSecret) {
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

    private static ApiException cooldown(EmailCodeCooldownException e) {
        return new ApiException(ApiErrorCode.EMAIL_CODE_COOLDOWN,
                Map.of("retryAfterSeconds", e.getRetryAfterSeconds()));
    }

    private static String requireEmail(JsonNode body, String field) {
        String value = requireText(body, field);
        if (value.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(value).matches()) {
            throw new ApiException(ApiErrorCode.EMAIL_INVALID_FORMAT);
        }
        return value;
    }

    private static String requireText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw ApiException.of(ApiErrorCode.MISSING_FIELD, "field", field);
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
