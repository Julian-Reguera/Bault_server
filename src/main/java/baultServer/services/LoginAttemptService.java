package baultServer.services;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;

/**
 * Protección contra fuerza bruta en {@code /api/auth/public/login/password}: cuenta
 * intentos fallidos por email y bloquea el email durante {@code lockout} tras
 * {@code maxFailures} fallos dentro de una ventana deslizante de {@code window}.
 *
 * <p>Estado <b>in-memory</b> (mismo patrón que {@code DevicePresenceRepository} y
 * {@code TransferPipeRepository}): no sobrevive a reinicios y no funciona entre
 * múltiples instancias sin store compartido. Para prod horizontal, migrar a
 * Redis / Caffeine + gossip.
 *
 * <p>Se cuenta por email (case-insensitive) y no por IP para evitar denegar a
 * usuarios legítimos detrás de NATs corporativos / móviles. Trade-off: un atacante
 * puede DoS-ear a un usuario concreto forzando su bloqueo con contraseñas malas.
 * Aceptable como primera línea; añadir por-IP o CAPTCHA si se observa abuso.
 */
@Service
public class LoginAttemptService {

    private final int maxFailures;
    private final Duration window;
    private final Duration lockout;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(
            @Value("${bault.security.login.max-failures:5}") int maxFailures,
            @Value("${bault.security.login.window-seconds:900}") long windowSeconds,
            @Value("${bault.security.login.lockout-seconds:300}") long lockoutSeconds) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofSeconds(windowSeconds);
        this.lockout = Duration.ofSeconds(lockoutSeconds);
    }

    /**
     * Lanza {@link ApiException} con {@link ApiErrorCode#LOGIN_RATE_LIMITED} si el
     * email está bloqueado. Los {@code details} incluyen {@code retryAfterSeconds}.
     */
    public void assertNotLocked(String email) {
        Attempts a = attempts.get(normalize(email));
        if (a == null || a.lockedUntil == null) return;
        Instant now = Instant.now();
        if (a.lockedUntil.isAfter(now)) {
            long retry = Duration.between(now, a.lockedUntil).toSeconds();
            throw new ApiException(ApiErrorCode.LOGIN_RATE_LIMITED,
                    Map.of("retryAfterSeconds", Math.max(1L, retry)));
        }
    }

    /**
     * Registra un intento fallido. Si es el {@code maxFailures}-ésimo dentro de la
     * ventana, activa el lockout. Se asume que el caller acaba de pasar
     * {@link #assertNotLocked(String)} (así que no estamos actualmente bloqueados).
     */
    public void recordFailure(String email) {
        Instant now = Instant.now();
        attempts.compute(normalize(email), (k, a) -> {
            int newCount = (a == null || now.isAfter(a.lastAt.plus(window))) ? 1 : a.count + 1;
            Instant lockedUntil = newCount >= maxFailures ? now.plus(lockout) : null;
            return new Attempts(now, newCount, lockedUntil);
        });
    }

    /** Login correcto: se limpia el contador para ese email. */
    public void recordSuccess(String email) {
        attempts.remove(normalize(email));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record Attempts(Instant lastAt, int count, Instant lockedUntil) {}
}
