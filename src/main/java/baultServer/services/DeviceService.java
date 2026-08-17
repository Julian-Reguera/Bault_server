package baultServer.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;
import baultServer.exceptions.DeviceAuthenticationException;
import baultServer.exceptions.DeviceBlockedException;
import baultServer.exceptions.DeviceRemovedException;
import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.DevicePresenceRepository;
import baultServer.repositorys.DeviceRepository;

@Service
public class DeviceService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int RAW_SECRET_BYTES = 32;
    private static final long DEACTIVATE_LOCK_DAYS = 7L;

    private final DeviceRepository repository;
    private final DevicePresenceRepository presenceRepository;
    private final RefreshTokenService refreshTokenService;
    private final EventWsBroadcaster eventBroadcaster;

    public DeviceService(DeviceRepository repository,
                         DevicePresenceRepository presenceRepository,
                         RefreshTokenService refreshTokenService,
                         EventWsBroadcaster eventBroadcaster) {
        this.repository = repository;
        this.presenceRepository = presenceRepository;
        this.refreshTokenService = refreshTokenService;
        this.eventBroadcaster = eventBroadcaster;
    }

    public List<Device> findByUser(User user) {
        return repository.findByUser(user);
    }

    public Device findByIdAndUser(Long deviceId, User user) {
        return repository.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> new ApiException(ApiErrorCode.DEVICE_NOT_FOUND));
    }

    public List<Device.Transfer> findByUserWithPresence(User user) {
        return repository.findByUser(user).stream()
                .map(d -> {
                    Device.Transfer t = d.toTransfer();
                    t.setOnline(presenceRepository.isOnline(d.getId()));
                    return t;
                })
                .toList();
    }

    public long countActive(User user) {
        return repository.countByUserAndStatus(user, Device.Status.ACTIVE);
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
        device.setCreatedAt(ZonedDateTime.now());
        device.setLastConnection(ZonedDateTime.now());
        device.setStatus(canAllocateActive(user) ? Device.Status.ACTIVE : Device.Status.DISABLED);
        repository.save(device);
        eventBroadcaster.deviceCreated(user.getId(), device);
        return new Registered(device, raw);
    }

    /**
     * Valida que el device pertenece al usuario y que el secreto presentado cuadra.
     * BLOCKED -> 403 (no reemite tokens ni cae a register).
     * REMOVED -> lanza DeviceRemovedException para que el caller registre uno nuevo.
     */
    public Device verify(User user, Long deviceId, String rawSecret) {
        Device device = repository.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> new DeviceAuthenticationException("Unknown device"));
        if (!constantTimeEquals(device.getSecretHash(), hash(rawSecret))) {
            throw new DeviceAuthenticationException("Invalid device secret");
        }
        switch (device.getStatus()) {
            case BLOCKED -> throw new DeviceBlockedException();
            case REMOVED -> throw new DeviceRemovedException();
            case ACTIVE, DISABLED -> { /* OK */ }
        }
        return device;
    }

    @Transactional
    public Device activate(Device device) {
        if (device.getStatus() == Device.Status.BLOCKED || device.getStatus() == Device.Status.REMOVED) {
            throw new ApiException(ApiErrorCode.DEVICE_STATE_TRANSITION_INVALID,
                    java.util.Map.of("action", "activate", "status", device.getStatus().name()));
        }
        if (device.getStatus() == Device.Status.ACTIVE) {
            return device;
        }
        if (!canAllocateActive(device.getUser())) {
            throw new ApiException(ApiErrorCode.DEVICE_PLAN_LIMIT_REACHED);
        }
        device.setStatus(Device.Status.ACTIVE);
        device.setLastActivatedAt(ZonedDateTime.now());
        Device saved = repository.save(device);
        eventBroadcaster.deviceUpdated(saved.getUser().getId(), saved);
        return saved;
    }

    @Transactional
    public Device deactivate(Device device) {
        if (device.getStatus() == Device.Status.BLOCKED || device.getStatus() == Device.Status.REMOVED) {
            throw new ApiException(ApiErrorCode.DEVICE_STATE_TRANSITION_INVALID,
                    java.util.Map.of("action", "deactivate", "status", device.getStatus().name()));
        }
        if (device.getStatus() == Device.Status.DISABLED) {
            return device;
        }
        ZonedDateTime lastActivated = device.getLastActivatedAt();
        if (lastActivated != null
                && lastActivated.plusDays(DEACTIVATE_LOCK_DAYS).isAfter(ZonedDateTime.now())) {
            throw new ApiException(ApiErrorCode.DEVICE_DEACTIVATE_LOCKED,
                    java.util.Map.of("lockDays", DEACTIVATE_LOCK_DAYS));
        }
        device.setStatus(Device.Status.DISABLED);
        Device saved = repository.save(device);
        eventBroadcaster.deviceUpdated(saved.getUser().getId(), saved);
        return saved;
    }

    @Transactional
    public Device block(Device device) {
        if (device.getStatus() == Device.Status.REMOVED) {
            throw new ApiException(ApiErrorCode.DEVICE_STATE_TRANSITION_INVALID,
                    java.util.Map.of("action", "block", "status", device.getStatus().name()));
        }
        if (device.getStatus() == Device.Status.BLOCKED) {
            return device;
        }
        device.setStatus(Device.Status.BLOCKED);
        Device saved = repository.save(device);
        refreshTokenService.revokeAllByDevice(saved);
        eventBroadcaster.deviceUpdated(saved.getUser().getId(), saved);
        return saved;
    }

    @Transactional
    public Device unblock(Device device) {
        if (device.getStatus() != Device.Status.BLOCKED) {
            throw new ApiException(ApiErrorCode.DEVICE_STATE_TRANSITION_INVALID,
                    java.util.Map.of("action", "unblock", "status", device.getStatus().name()));
        }
        device.setStatus(Device.Status.DISABLED);
        Device saved = repository.save(device);
        eventBroadcaster.deviceUpdated(saved.getUser().getId(), saved);
        return saved;
    }

    @Transactional
    public Device remove(Device device) {
        if (device.getStatus() == Device.Status.REMOVED) {
            return device;
        }
        device.setStatus(Device.Status.REMOVED);
        Device saved = repository.save(device);
        refreshTokenService.revokeAllByDevice(saved);
        eventBroadcaster.deviceRemoved(saved.getUser().getId(), saved.getId());
        return saved;
    }

    @Transactional
    public Device rename(Device device, String alias) {
        device.setAlias(alias);
        Device saved = repository.save(device);
        eventBroadcaster.deviceUpdated(saved.getUser().getId(), saved);
        return saved;
    }

    /**
     * Refresca lastConnection y actualiza operatingSystem/appVersion si vienen no nulos.
     * Usado por login (rama verify) y refresh para mantener la metadata del device al día.
     */
    @Transactional
    public Device touchDeviceInfo(Device device, String operatingSystem, String appVersion) {
        if (operatingSystem != null) device.setOperatingSystem(operatingSystem);
        if (appVersion != null) device.setAppVersion(appVersion);
        device.setLastConnection(ZonedDateTime.now());
        return repository.save(device);
    }

    private boolean canAllocateActive(User user) {
        BillingPlan plan = user.getBillingPlan();
        if (plan == null) return false;
        int max = plan.getMaxDevices();
        if (max <= 0) return true;
        return countActive(user) < max;
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

    public record Registered(Device device, String rawSecret) {}
}
