package baultServer.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import baultServer.exceptions.EmailCodeCooldownException;
import baultServer.exceptions.EmailCodeExpiredException;
import baultServer.exceptions.EmailCodeInvalidException;
import baultServer.exceptions.EmailCodeNotFoundException;
import baultServer.model.EmailCode;
import baultServer.model.User;
import baultServer.repositorys.EmailCodeRepository;
import baultServer.services.email.EmailService;
import baultServer.utils.EmailTemplates;
import baultServer.utils.EmailTemplates.Rendered;

/**
 * Emite, envía y valida códigos de un solo uso enviados por correo.
 * - Cooldown entre reenvíos por (user, purpose).
 * - TTL configurable.
 * - Máximo de intentos antes de invalidar el código.
 * - Códigos siempre almacenados como hash SHA-256 (nunca en plano).
 */
@Service
public class EmailCodeService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;

    private final EmailCodeRepository repository;
    private final EmailService emailService;
    private final EmailTemplates templates;
    private final int ttlMinutes;
    private final int cooldownSeconds;
    private final int codeLength;

    public EmailCodeService(EmailCodeRepository repository,
                            EmailService emailService,
                            EmailTemplates templates,
                            @Value("${bault.email.verification.ttl-minutes:15}") int ttlMinutes,
                            @Value("${bault.email.verification.cooldown-seconds:60}") int cooldownSeconds,
                            @Value("${bault.email.verification.code-length:6}") int codeLength) {
        this.repository = repository;
        this.emailService = emailService;
        this.templates = templates;
        this.ttlMinutes = ttlMinutes;
        this.cooldownSeconds = cooldownSeconds;
        this.codeLength = codeLength;
    }

    /**
     * Genera y envía un código para el propósito indicado.
     * Aplica cooldown: si ya se emitió uno recientemente, responde 429.
     * Invalida los códigos previos activos del mismo (user, purpose).
     */
    @Transactional
    public void issueAndSend(User user, EmailCode.Purpose purpose) {
        ZonedDateTime now = ZonedDateTime.now();

        Optional<EmailCode> latest = repository.findLatest(user, purpose);
        latest.ifPresent(prev -> {
            Duration since = Duration.between(prev.getCreatedAt(), now);
            long remaining = cooldownSeconds - since.getSeconds();
            if (remaining > 0) {
                throw new EmailCodeCooldownException(remaining);
            }
        });

        //Invalida cualquier código previo activo para forzar que sólo uno esté vigente.
        repository.consumeAllActive(user, purpose, now);

        String rawCode = generateNumericCode(codeLength);
        EmailCode entity = new EmailCode();
        entity.setUser(user);
        entity.setPurpose(purpose);
        entity.setCodeHash(hash(rawCode));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusMinutes(ttlMinutes));
        repository.save(entity);

        Rendered r = switch (purpose) {
            case EMAIL_VERIFICATION -> templates.verificationCode(rawCode, ttlMinutes);
            case PASSWORD_RESET -> templates.passwordResetCode(rawCode, ttlMinutes);
        };
        emailService.send(user.getEmail(), r.subject(), r.html(), r.text());
    }

    /**
     * Verifica un código presentado por el usuario. Consume el código si es válido.
     * Cuenta intentos fallidos: al superar MAX_ATTEMPTS invalida el código.
     */
    @Transactional
    public void verifyAndConsume(User user, EmailCode.Purpose purpose, String rawCode) {
        EmailCode code = repository.findLatestActive(user, purpose)
                .orElseThrow(EmailCodeNotFoundException::new);

        ZonedDateTime now = ZonedDateTime.now();
        if (code.getExpiresAt().isBefore(now)) {
            code.setConsumedAt(now);
            repository.save(code);
            throw new EmailCodeExpiredException();
        }

        if (!constantTimeEquals(code.getCodeHash(), hash(rawCode))) {
            code.setAttempts(code.getAttempts() + 1);
            if (code.getAttempts() >= MAX_ATTEMPTS) {
                code.setConsumedAt(now);
            }
            repository.save(code);
            throw new EmailCodeInvalidException();
        }

        code.setConsumedAt(now);
        repository.save(code);
    }

    private String generateNumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RNG.nextInt(10));
        }
        return sb.toString();
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
