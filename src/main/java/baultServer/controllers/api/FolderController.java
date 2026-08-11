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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import baultServer.configs.http.JwtAuthenticationFilter;
import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.User;
import baultServer.repositorys.FolderRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.DeviceRpcService;
import baultServer.services.DeviceService;
import baultServer.services.FolderService;
import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final UserRepository userRepository;
    private final DeviceService deviceService;
    private final FolderService folderService;
    private final FolderRepository folderRepository;
    private final DeviceRpcService deviceRpcService;

    public FolderController(UserRepository userRepository,
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

    @GetMapping(produces = "application/json")
    public Map<String, Object> listUserSharedFolders(@AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folders", folderService.findSharedByUser(user));
        return resultado;
    }

    @GetMapping(path = "/device/{deviceId}", produces = "application/json")
    public Map<String, Object> listSharedFolders(@PathVariable Long deviceId,
                                                 @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Device device = deviceService.findByIdAndUser(deviceId, user);
        Map<String, Object> resultado = new HashMap<>();
        
        resultado.put("folders", folderService.findSharedByDevice(device));
        resultado.put("folder-status", device.getStatus().toString());
        return resultado;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Folder.Transfer createFolder(@RequestBody JsonNode body,
                                        @AuthenticationPrincipal UserDetails principal,
                                        HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = requireCallerDevice(user, request);

        JsonNode pathNode = body == null ? null : body.get("path");
        if (pathNode == null || pathNode.isNull() || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing or invalid field: path");
        }
        Folder.Sharing sharing = parseSharing(body.get("sharing"), Folder.Sharing.NONE);
        boolean encrypted = body.hasNonNull("encrypted") && body.get("encrypted").asBoolean(false);
        return folderService.create(caller, pathNode.asText(), sharing, encrypted).toTransfer();
    }

    @GetMapping(path = "/shared-with-me", produces = "application/json")
    public Map<String, Object> listFoldersSharedWithMe(@AuthenticationPrincipal UserDetails principal,
                                                       HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = requireCallerDevice(user, request);
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folders", folderService.findSharedWithDevice(user, caller));
        return resultado;
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long folderId,
                                             @AuthenticationPrincipal UserDetails principal,
                                             HttpServletRequest request) {
        User user = currentUser(principal);
        Folder folder = loadFolderOwnedByCaller(folderId, user, request);

        if(!folder.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, "Folder is disabled");
        }

        folderService.unshare(folder);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(path = "/{folderId}", consumes = "application/json", produces = "application/json")
    public Folder.Transfer updateFolderSharing(@PathVariable Long folderId,
                                               @RequestBody JsonNode body,
                                               @AuthenticationPrincipal UserDetails principal,
                                               HttpServletRequest request) {
        User user = currentUser(principal);
        Folder folder = loadFolderOwnedByCaller(folderId, user, request);

        if(!folder.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, "Folder is disabled");
        }

        JsonNode sharingNode = body == null ? null : body.get("sharing");
        if (sharingNode == null || sharingNode.isNull()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing field: sharing");
        }
        Folder.Sharing sharing = parseSharing(sharingNode, null);
        if (sharing == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid sharing value");
        }
        return folderService.updateSharing(folder, sharing).toTransfer();
    }

    @GetMapping(path = "/browse/{folderId}", produces = "application/json")
    public Map<String, Object> listFolderEntries(@PathVariable Long folderId,
                                                 @RequestParam(required = false) String path,
                                                 @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Folder not found"));

        if(!folder.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, "Folder is disabled");
        }
        
        Device ownerDevice = folder.getDevice();
        if (!ownerDevice.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Folder does not belong to user");
        }
        if (!folder.isEnabled() || folder.getSharing() == null
                || !folder.getSharing().allows(Folder.Sharing.READ)) {
            throw new ResponseStatusException(FORBIDDEN, "Folder not readable");
        }

        String safePath = normalizeSubPath(path);
        JsonNode entries = deviceRpcService.listFolderEntries(ownerDevice, folderId, safePath);

        Map<String, Object> resultado = new HashMap<>();
        resultado.put("folderId", folderId);
        resultado.put("path", path == null ? "/" : path);
        resultado.put("entries", entries);
        return resultado;
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }

    private Device requireCallerDevice(User user, HttpServletRequest request) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.DEVICE_ID_ATTR);
        if (!(attr instanceof Long callerDeviceId)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing device in token");
        }
        return deviceService.findByIdAndUser(callerDeviceId, user);
    }

    private Folder loadFolderOwnedByCaller(Long folderId, User user, HttpServletRequest request) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Folder not found"));
        if (!folder.getDevice().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Folder does not belong to user");
        }
        Object attr = request.getAttribute(JwtAuthenticationFilter.DEVICE_ID_ATTR);
        if (!(attr instanceof Long callerDeviceId)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing device in token");
        }
        if (!callerDeviceId.equals(folder.getDevice().getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the owner device may modify this folder");
        }
        return folder;
    }

    private static Folder.Sharing parseSharing(JsonNode node, Folder.Sharing fallback) {
        if (node == null || node.isNull() || !node.isTextual()) return fallback;
        try {
            return Folder.Sharing.valueOf(node.asText().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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
