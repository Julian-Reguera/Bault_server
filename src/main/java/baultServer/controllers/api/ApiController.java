package baultServer.controllers.api;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.User;
import baultServer.repositorys.FolderRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceRpcService;
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
    private final FolderRepository folderRepository;
    private final DeviceRpcService deviceRpcService;

    public ApiController(UserRepository userRepository,
                         DeviceService deviceService,
                         FolderService folderService,
                         FolderRepository folderRepository,
                         DeviceRpcService deviceRpcService) {
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.folderService = folderService;
        this.folderRepository = folderRepository;
        this.deviceRpcService = deviceRpcService;
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

    @GetMapping(path = "/folders/{folderId}/entries", produces = "application/json")
    @ResponseBody
    public Map<String, Object> listFolderEntries(@PathVariable Long folderId,
                                                 @RequestParam(required = false) String path,
                                                 @AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Folder not found"));

        Device ownerDevice = folder.getDevice();
        if (!ownerDevice.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Folder does not belong to user");
        }
        if (!folder.isEnabled() || !folder.isShared()) {
            throw new ResponseStatusException(FORBIDDEN, "Folder not shared");
        }

        String safePath = normalizeSubPath(path);
        JsonNode entries = deviceRpcService.listFolderEntries(ownerDevice, folderId, safePath);

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folderId", folderId);
        resultado.put("path", path == null ? "/" : path);
        resultado.put("entries", entries);
        return resultado;
    }

    private static String normalizeSubPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.replace('\\', '/').trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new ResponseStatusException(FORBIDDEN, "Path traversal not allowed");
            }
        }
        return normalized;
    }
}
