package baultServer.controllers.api;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;
import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.DevicePresenceRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceService;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final UserRepository userRepository;
    private final DeviceService deviceService;
    private final DevicePresenceRepository presenceRepository;

    public DeviceController(UserRepository userRepository,
                            DeviceService deviceService,
                            DevicePresenceRepository presenceRepository) {
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.presenceRepository = presenceRepository;
    }

    @GetMapping(produces = "application/json")
    @ResponseBody
    public Map<String, Object> list(@AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Map<String, Object> result = new HashMap<>();
        result.put("devices", deviceService.findByUserWithPresence(user));
        return result;
    }

    @GetMapping(path = "/{deviceId}", produces = "application/json")
    @ResponseBody
    public Device.Transfer detail(@PathVariable Long deviceId,
                                  @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        Device.Transfer dto = device.toTransfer();
        dto.setOnline(presenceRepository.isOnline(device.getId()));
        return dto;
    }

    @PatchMapping(path = "/{deviceId}", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public Device.Transfer rename(@PathVariable Long deviceId,
                                  @RequestBody JsonNode body,
                                  @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);

        JsonNode aliasNode = body == null ? null : body.get("alias");
        if (aliasNode == null || aliasNode.isNull() || !aliasNode.isTextual()) {
            throw ApiException.of(ApiErrorCode.INVALID_FIELD, "field", "alias");
        }

        Device updated = deviceService.rename(device, aliasNode.asText());
        Device.Transfer dto = updated.toTransfer();
        dto.setOnline(presenceRepository.isOnline(updated.getId()));
        return dto;
    }

    @PostMapping(path = "/{deviceId}/activate", produces = "application/json")
    @ResponseBody
    public Device.Transfer activate(@PathVariable Long deviceId,
                                    @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        Device updated = deviceService.activate(device);
        Device.Transfer dto = updated.toTransfer();
        dto.setOnline(presenceRepository.isOnline(updated.getId()));
        return dto;
    }

    @PostMapping(path = "/{deviceId}/deactivate", produces = "application/json")
    @ResponseBody
    public Device.Transfer deactivate(@PathVariable Long deviceId,
                                      @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        Device updated = deviceService.deactivate(device);
        Device.Transfer dto = updated.toTransfer();
        dto.setOnline(presenceRepository.isOnline(updated.getId()));
        return dto;
    }

    @PostMapping(path = "/{deviceId}/block", produces = "application/json")
    @ResponseBody
    public Device.Transfer block(@PathVariable Long deviceId,
                                 @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        Device updated = deviceService.block(device);
        Device.Transfer dto = updated.toTransfer();
        dto.setOnline(presenceRepository.isOnline(updated.getId()));
        return dto;
    }

    @PostMapping(path = "/{deviceId}/unblock", produces = "application/json")
    @ResponseBody
    public Device.Transfer unblock(@PathVariable Long deviceId,
                                   @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        Device updated = deviceService.unblock(device);
        Device.Transfer dto = updated.toTransfer();
        dto.setOnline(presenceRepository.isOnline(updated.getId()));
        return dto;
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> remove(@PathVariable Long deviceId,
                                       @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        requireNotRemoved(device);
        deviceService.remove(device);
        return ResponseEntity.noContent().build();
    }

    private static void requireNotRemoved(Device device) {
        if (device.getStatus() == Device.Status.REMOVED) {
            throw new ApiException(ApiErrorCode.DEVICE_NOT_FOUND);
        }
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ApiException(ApiErrorCode.USER_NOT_FOUND));
    }
}
