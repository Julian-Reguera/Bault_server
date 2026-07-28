package baultServer.controllers;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceService;
import baultServer.services.FolderService;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final UserRepository userRepository;
    private final DeviceService deviceService;
    private final FolderService folderService;

    public ApiController(UserRepository userRepository,
                         DeviceService deviceService,
                         FolderService folderService) {
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.folderService = folderService;
    }

    @GetMapping(path = "/users/{userId}/devices", produces = "application/json")
    @ResponseBody
    public Map<String, Object> listDevices(@PathVariable Long userId,
                                           @AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (!user.getId().equals(userId)) {
            throw new ResponseStatusException(FORBIDDEN, "User mismatch");
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("devices", deviceService.findByUserWithPresence(user));
        return resultado;
    }

    @GetMapping(path = "/users/{userId}/folders", produces = "application/json")
    @ResponseBody
    public Map<String, Object> listUserSharedFolders(@PathVariable Long userId,
                                                     @AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (!user.getId().equals(userId)) {
            throw new ResponseStatusException(FORBIDDEN, "User mismatch");
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folders", folderService.findSharedByUser(user));
        return resultado;
    }

    @GetMapping(path = "/devices/{deviceId}/folders", produces = "application/json")
    @ResponseBody
    public Map<String, Object> listSharedFolders(@PathVariable Long deviceId,
                                                 @AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        Device device = deviceService.findByIdAndUser(deviceId, user);
        if (!device.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, "Device disabled");
        }

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folders", folderService.findSharedByDevice(device));
        return resultado;
    }
}
