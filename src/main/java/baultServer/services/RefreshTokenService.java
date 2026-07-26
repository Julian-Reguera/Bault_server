package baultServer.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import baultServer.model.RefreshToken;
import baultServer.model.User;
import baultServer.repositorys.RefreshTokenRepository;

@Service
public class RefreshTokenService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int RAW_TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final long expirationMs;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${bault.jwt.refresh.expiration-ms}") long expirationMs) {
        this.repository = repository;
        this.expirationMs = expirationMs;
    }

    @Transactional
    public String issue(User user) {
        String raw = generateRawToken();
        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(raw));
        entity.setCreatedAt(ZonedDateTime.now());
        entity.setExpiresAt(ZonedDateTime.now().plusNanos(expirationMs * 1_000_000L));
        entity.setRevoked(false);
        repository.save(entity);
        return raw;
    }

    /**
     * Validates the presented raw refresh token, revokes it, and issues a new one.
     * If the token was already revoked, revokes every refresh for that user
     * (reuse detection: assume compromise, force re-login on all sessions).
     */
    @Transactional
    public Rotated rotate(String rawToken) {
        RefreshToken current = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token"));

        if (current.isRevoked()) {
            repository.revokeAllByUser(current.getUser());
            throw unauthorized("Refresh token reuse detected");
        }

        if (current.getExpiresAt().isBefore(ZonedDateTime.now())) {
            throw unauthorized("Refresh token expired");
        }

        current.setRevoked(true);
        repository.save(current);

        String newRaw = issue(current.getUser());
        return new Rotated(current.getUser(), newRaw);
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(rt -> {
            rt.setRevoked(true);
            repository.save(rt);
        });
    }

    @Transactional
    public void revokeAllForUser(User user) {
        repository.revokeAllByUser(user);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private ResponseStatusException unauthorized(String msg) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, msg);
    }

    public record Rotated(User user, String rawToken) {}
}
