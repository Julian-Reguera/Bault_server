package baultServer.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.DeviceRepository;

@Service
public class DeviceService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int RAW_SECRET_BYTES = 32;

    private final DeviceRepository repository;

    public DeviceService(DeviceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Registered register(User user, String alias, String operatingSystem, String appVersion) {
        String raw = generateRawSecret();
        Device device = new Device();
        device.setUser(user);
        device.setAlias(alias);
        device.setOperatingSystem(operatingSystem);
        device.setAppVersion(appVersion);
        device.setSecretHash(hash(raw));
        device.setEnabled(true);
        device.setTrusted(false);
        device.setCreatedAt(ZonedDateTime.now());
        device.setLastConnection(ZonedDateTime.now());
        repository.save(device);
        return new Registered(device, raw);
    }

    /**
     * Valida que el device pertenece al usuario y que el secreto presentado cuadra.
     * Lanza 401 si algo no cuadra.
     */
    public Device verify(User user, Long deviceId, String rawSecret) {
        Device device = repository.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> unauthorized("Unknown device"));
        if (!device.isEnabled()) {
            throw unauthorized("Device disabled");
        }
        if (!constantTimeEquals(device.getSecretHash(), hash(rawSecret))) {
            throw unauthorized("Invalid device secret");
        }

        return device;
    }

    private String generateRawSecret() {
        byte[] bytes = new byte[RAW_SECRET_BYTES];
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

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private static ResponseStatusException unauthorized(String msg) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, msg);
    }

    public record Registered(Device device, String rawSecret) {}
}
