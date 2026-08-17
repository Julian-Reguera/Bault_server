package baultServer.controllers.api;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import baultServer.configs.http.JwtAuthenticationFilter;
import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;
import baultServer.exceptions.UploaderDenialException;
import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.Transfer;
import baultServer.model.User;
import baultServer.repositorys.DevicePresenceRepository;
import baultServer.repositorys.DeviceRepository;
import baultServer.repositorys.FolderRepository;
import baultServer.repositorys.TransferPipeRepository;
import baultServer.repositorys.TransferRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.CryptoService;
import baultServer.services.EventWsBroadcaster;
import baultServer.services.TransferHistoryService;
import baultServer.services.TransferHistoryService.HistoryFilter;
import baultServer.services.TransferHistoryService.HistoryPage;
import baultServer.services.TransferHistoryService.StatusFilter;
import baultServer.utils.streams.CountingOutputStream;
import baultServer.utils.streams.StreamingPipe;
import baultServer.utils.streams.ThrottledOutputStream;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private static final long RENDEZVOUS_TIMEOUT_MS = 30_000L;
    private static final long METADATA_TIMEOUT_MS = 30_000L;

    /** 1 megabit = 125 000 bytes/s. */
    private static final long BYTES_PER_MBPS = 125_000L;

    /** 1 MB (megabyte, base 10) para maxTraffic del plan. */
    private static final long BYTES_PER_MB = 1_000_000L;

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final FolderRepository folderRepository;
    private final TransferRepository transferRepository;
    private final DevicePresenceRepository presenceRepository;
    private final TransferPipeRepository pipeRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final CryptoService cryptoService;
    private final TransferHistoryService historyService;
    private final EventWsBroadcaster eventBroadcaster;
    /** Techo global opcional (Mbps); 0 = sin techo global, se respeta solo el plan. */
    private final long serverCeilingBytesPerSecond;

    public TransferController(UserRepository userRepository,
                              DeviceRepository deviceRepository,
                              FolderRepository folderRepository,
                              TransferRepository transferRepository,
                              DevicePresenceRepository presenceRepository,
                              TransferPipeRepository pipeRegistry,
                              SimpMessagingTemplate messagingTemplate,
                              CryptoService cryptoService,
                              TransferHistoryService historyService,
                              EventWsBroadcaster eventBroadcaster,
                              @Value("${bault.transfer.download.max-mbps:0}") double downloadMaxMbps) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.folderRepository = folderRepository;
        this.transferRepository = transferRepository;
        this.presenceRepository = presenceRepository;
        this.pipeRegistry = pipeRegistry;
        this.messagingTemplate = messagingTemplate;
        this.cryptoService = cryptoService;
        this.historyService = historyService;
        this.eventBroadcaster = eventBroadcaster;
        this.serverCeilingBytesPerSecond = downloadMaxMbps <= 0 ? 0L
                : (long) (downloadMaxMbps * BYTES_PER_MBPS);
    }

    // ===================== Histórico =====================

    @GetMapping(produces = "application/json")
    @ResponseBody
    public HistoryPage listHistory(@RequestParam(required = false, defaultValue = "all") String status,
                                   @RequestParam(required = false) Long deviceId,
                                   @RequestParam(required = false) Long senderId,
                                   @RequestParam(required = false) Long receiverId,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer size,
                                   @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        HistoryFilter filter = new HistoryFilter(parseStatus(status), deviceId, senderId, receiverId, q);
        return historyService.list(user, filter, cursor, size);
    }

    @PostMapping(path = "/{transferId}/retry", produces = "application/json")
    @ResponseBody
    public Map<String, Object> retry(@PathVariable Long transferId,
                                     @AuthenticationPrincipal UserDetails principal,
                                     HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);
        Transfer copy = historyService.retry(transferId, user, caller);
        return Map.of("transferId", copy.getId());
    }

    @GetMapping(path = "/export-csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) Long receiverId,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        HistoryFilter filter = new HistoryFilter(parseStatus(status), deviceId, senderId, receiverId, q);

        String filename = "bault-historico-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());

        StreamingResponseBody body = out -> {
            java.io.Writer w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
            historyService.streamCsv(user, filter, w);
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static StatusFilter parseStatus(String s) {
        if (s == null || s.isBlank()) return StatusFilter.ALL;
        try {
            return StatusFilter.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ApiErrorCode.TRANSFER_INVALID_STATUS_FILTER,
                    Map.of("value", s));
        }
    }

    // ===================== Solicitar transferencia =====================

    @PostMapping(path = "/download-request", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public Map<String, Object> downloadRequest(@RequestBody JsonNode body,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                               @AuthenticationPrincipal UserDetails principal,
                                               HttpServletRequest request) {
        User user = currentUser(principal);
        Device receiver = currentDevice(request, user);

        Map<String, Object> idem = idempotentResponse(receiver, idempotencyKey);
        if (idem != null) return idem;

        Long senderDeviceId = requireLong(body, "senderDeviceId");
        Long originFolderId = requireLong(body, "originFolderId");
        String originPath = requireText(body, "originPath");
        String destinationPath = optionalText(body, "destinationPath");
        long fileSize = requireNonNegativeSize(body);

        Device sender = loadPeerDevice(senderDeviceId, user);
        if (sender.getId().equals(receiver.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_PEERS_MUST_DIFFER);
        }

        Folder originFolder = loadFolderWithPermission(originFolderId, sender, Folder.Sharing.READ);

        if (!presenceRepository.isOnline(sender.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "sender"));
        }

        checkMonthlyQuota(user, fileSize);

        Transfer transfer = newPendingTransfer(sender, receiver, receiver, originPath, destinationPath, idempotencyKey);
        transfer.setOriginFolder(originFolder);
        transferRepository.save(transfer);
        eventBroadcaster.transferCreated(user.getId(), transfer.getId(), transfer.getStatus());

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("transferId", transfer.getId());
        data.put("requesterDeviceId", receiver.getId());
        data.put("originFolderId", originFolder.getId());
        data.put("originPath", originPath);
        if (destinationPath != null) data.put("destinationPath", destinationPath);
        data.put("sizeBytes", fileSize);
        notifyDevice(sender.getId(), "transfer.upload-requested", data);

        return Map.of("transferId", transfer.getId());
    }

    @PostMapping(path = "/upload-request", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public Map<String, Object> uploadRequest(@RequestBody JsonNode body,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                             @AuthenticationPrincipal UserDetails principal,
                                             HttpServletRequest request) {
        User user = currentUser(principal);
        Device sender = currentDevice(request, user);

        Map<String, Object> idem = idempotentResponse(sender, idempotencyKey);
        if (idem != null) return idem;

        Long receiverDeviceId = requireLong(body, "receiverDeviceId");
        Long destinationFolderId = requireLong(body, "destinationFolderId");
        String originPath = requireText(body, "originPath");
        String destinationPath = optionalText(body, "destinationPath");
        long fileSize = requireNonNegativeSize(body);

        Device receiver = loadPeerDevice(receiverDeviceId, user);
        if (sender.getId().equals(receiver.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_PEERS_MUST_DIFFER);
        }

        Folder destinationFolder = loadFolderWithPermission(destinationFolderId, receiver,
                Folder.Sharing.READ_WRITE);

        if (!presenceRepository.isOnline(receiver.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "receiver"));
        }

        checkMonthlyQuota(user, fileSize);

        Transfer transfer = newPendingTransfer(sender, receiver, sender, originPath, destinationPath, idempotencyKey);
        transfer.setDestinationFolder(destinationFolder);
        transferRepository.save(transfer);
        eventBroadcaster.transferCreated(user.getId(), transfer.getId(), transfer.getStatus());

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("transferId", transfer.getId());
        data.put("requesterDeviceId", sender.getId());
        data.put("destinationFolderId", destinationFolder.getId());
        data.put("originPath", originPath);
        if (destinationPath != null) data.put("destinationPath", destinationPath);
        data.put("sizeBytes", fileSize);
        notifyDevice(receiver.getId(), "transfer.download-offered", data);

        return Map.of("transferId", transfer.getId());
    }

    /**
     * Third-party: el device actual (owner) orquesta una transferencia entre otros dos devices
     * del mismo usuario (sender y receiver), sin ser ninguno de los dos. Requiere ambas carpetas
     * compartidas: la de origen con READ, la de destino con READ_WRITE.
     */
    @PostMapping(path = "/third-party-request", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public Map<String, Object> thirdPartyRequest(@RequestBody JsonNode body,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @AuthenticationPrincipal UserDetails principal,
                                                 HttpServletRequest request) {
        User user = currentUser(principal);
        Device owner = currentDevice(request, user);

        Map<String, Object> idem = idempotentResponse(owner, idempotencyKey);
        if (idem != null) return idem;

        Long senderDeviceId = requireLong(body, "senderDeviceId");
        Long receiverDeviceId = requireLong(body, "receiverDeviceId");
        Long originFolderId = requireLong(body, "originFolderId");
        Long destinationFolderId = requireLong(body, "destinationFolderId");
        String originPath = requireText(body, "originPath");
        String destinationPath = optionalText(body, "destinationPath");
        long fileSize = requireNonNegativeSize(body);

        Device sender = loadPeerDevice(senderDeviceId, user);
        Device receiver = loadPeerDevice(receiverDeviceId, user);
        if (sender.getId().equals(receiver.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_PEERS_MUST_DIFFER);
        }
        if (owner.getId().equals(sender.getId()) || owner.getId().equals(receiver.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_OWNER_MUST_DIFFER_FROM_PEERS);
        }

        Folder originFolder = loadFolderWithPermission(originFolderId, sender, Folder.Sharing.READ);
        Folder destinationFolder = loadFolderWithPermission(destinationFolderId, receiver,
                Folder.Sharing.READ_WRITE);

        if (!presenceRepository.isOnline(sender.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "sender"));
        }
        if (!presenceRepository.isOnline(receiver.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "receiver"));
        }

        checkMonthlyQuota(user, fileSize);

        Transfer transfer = newPendingTransfer(sender, receiver, owner, originPath, destinationPath, idempotencyKey);
        transfer.setOriginFolder(originFolder);
        transfer.setDestinationFolder(destinationFolder);
        transferRepository.save(transfer);
        eventBroadcaster.transferCreated(user.getId(), transfer.getId(), transfer.getStatus());

        //Notificar a ambos peers: sender debe hacer /upload, receiver debe hacer /download.
        ObjectNode senderData = JsonNodeFactory.instance.objectNode();
        senderData.put("transferId", transfer.getId());
        senderData.put("requesterDeviceId", owner.getId());
        senderData.put("originFolderId", originFolder.getId());
        senderData.put("originPath", originPath);
        if (destinationPath != null) senderData.put("destinationPath", destinationPath);
        senderData.put("sizeBytes", fileSize);
        senderData.put("thirdParty", true);
        notifyDevice(sender.getId(), "transfer.upload-requested", senderData);

        ObjectNode receiverData = JsonNodeFactory.instance.objectNode();
        receiverData.put("transferId", transfer.getId());
        receiverData.put("requesterDeviceId", owner.getId());
        receiverData.put("destinationFolderId", destinationFolder.getId());
        receiverData.put("originPath", originPath);
        if (destinationPath != null) receiverData.put("destinationPath", destinationPath);
        receiverData.put("sizeBytes", fileSize);
        receiverData.put("thirdParty", true);
        notifyDevice(receiver.getId(), "transfer.download-offered", receiverData);

        return Map.of("transferId", transfer.getId());
    }

    /**
     * Devuelve las transferencias PENDING third-party en las que el device actual participa
     * (como sender o receiver) pero no es el owner. Sirve para descubrir peticiones creadas
     * por un tercer device del mismo usuario que se pudieron perder por estar offline.
     */
    @GetMapping(path = "/pending-as-peer", produces = "application/json")
    @ResponseBody
    public Map<String, Object> pendingAsPeer(@AuthenticationPrincipal UserDetails principal,
                                             HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);

        java.util.List<Map<String, Object>> items = transferRepository
                .findThirdPartyByPeerAndStatus(caller.getId(), Transfer.Status.PENDING)
                .stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("transferId", t.getId());
                    m.put("ownerDeviceId", t.getOwner().getId());
                    m.put("senderDeviceId", t.getSender().getId());
                    m.put("receiverDeviceId", t.getReceiver().getId());
                    m.put("role", caller.getId().equals(t.getSender().getId()) ? "sender" : "receiver");
                    m.put("originFolderId", t.getOriginFolder() == null ? null : t.getOriginFolder().getId());
                    m.put("destinationFolderId", t.getDestinationFolder() == null ? null : t.getDestinationFolder().getId());
                    m.put("originPath", t.getOriginPath());
                    m.put("destinationPath", t.getDestinationPath());
                    m.put("sizeBytes", t.getSizeBytes());
                    m.put("createdAt", t.getCreatedAt());
                    return m;
                })
                .toList();
        return Map.of("transfers", items);
    }

    @GetMapping(path = "/{transferId}/keys", produces = "application/json")
    @ResponseBody
    public ObjectNode transferKeys(@PathVariable Long transferId,
                                   @AuthenticationPrincipal UserDetails principal,
                                   HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.TRANSFER_NOT_FOUND));
        if (!transfer.getOwner().getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_NOT_OWNED_BY_USER);
        }
        boolean isSender = transfer.getSender() != null
                && caller.getId().equals(transfer.getSender().getId());
        boolean isReceiver = transfer.getReceiver() != null
                && caller.getId().equals(transfer.getReceiver().getId());
        if (!isSender && !isReceiver) {
            throw new ApiException(ApiErrorCode.TRANSFER_KEYS_FORBIDDEN);
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("origin", folderKeyNode(transfer.getOriginFolder(), isSender));
        root.set("destination", folderKeyNode(transfer.getDestinationFolder(), isReceiver));
        return root;
    }

    private ObjectNode folderKeyNode(Folder folder, boolean includeDek) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (folder == null || folder.getWrappedDek() == null) {
            node.put("encrypted", false);
            return node;
        }
        node.put("encrypted", true);
        node.put("algorithm", folder.getEncryptionAlgorithm());
        if (includeDek) {
            byte[] dek = cryptoService.unwrap(folder.getWrappedDek(), folder.getDekWrapIv(),
                    folder.getKeyVersion(), folder.getId());
            try {
                node.put("dek", Base64.getEncoder().encodeToString(dek));
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        }
        return node;
    }

    @DeleteMapping("/{transferId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long transferId,
                                       @AuthenticationPrincipal UserDetails principal,
                                       HttpServletRequest request) {
        User user = currentUser(principal);
        currentDevice(request, user);

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.TRANSFER_NOT_FOUND));

        if (!transfer.getOwner().getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_NOT_OWNED_BY_USER);
        }
        if (transfer.getStatus() != Transfer.Status.PENDING) {
            throw new ApiException(ApiErrorCode.TRANSFER_STATE_CONFLICT,
                    Map.of("action", "cancel", "status", transfer.getStatus().name()));
        }

        transfer.setStatus(Transfer.Status.CANCELLED);
        transfer.setFailureReason("Cancelled by user");
        transfer.setCompletedAt(ZonedDateTime.now());
        transferRepository.save(transfer);
        eventBroadcaster.transferUpdated(user.getId(), transfer.getId(), transfer.getStatus());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{transferId}/deny")
    public ResponseEntity<Void> deny(@PathVariable Long transferId,
                                     @AuthenticationPrincipal UserDetails principal,
                                     HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.TRANSFER_NOT_FOUND));

        if (!transfer.getOwner().getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_NOT_OWNED_BY_USER);
        }

        Device peer = transfer.getOwner().getId().equals(transfer.getSender().getId())
                ? transfer.getReceiver() : transfer.getSender();
        if (!caller.getId().equals(peer.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_ONLY_PEER_MAY_DENY);
        }

        if (transfer.getStatus() != Transfer.Status.PENDING
                && transfer.getStatus() != Transfer.Status.IN_PROGRESS) {
            throw new ApiException(ApiErrorCode.TRANSFER_STATE_CONFLICT,
                    Map.of("action", "deny", "status", transfer.getStatus().name()));
        }

        transfer.setStatus(Transfer.Status.DENIED);
        transfer.setFailureReason("Denied by peer");
        transfer.setCompletedAt(ZonedDateTime.now());
        transferRepository.save(transfer);
        eventBroadcaster.transferUpdated(user.getId(), transfer.getId(), transfer.getStatus());

        StreamingPipe pipe = pipeRegistry.get(transferId);
        if (pipe != null) {
            pipe.abort(new RuntimeException("Denied by peer"));
        }

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("transferId", transferId);
        data.put("reason", "Denied by peer");
        notifyDevice(transfer.getOwner().getId(), "transfer.denied", data);

        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{transferId}/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable Long transferId,
                                       @RequestHeader(name = "X-Filename", required = false) String filename,
                                       @RequestHeader(name = "X-Content-Type", required = false) String contentType,
                                       @RequestHeader(name = "Content-Length", required = false) Long declaredSize,
                                       @AuthenticationPrincipal UserDetails principal,
                                       HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.TRANSFER_NOT_FOUND));
        //Upload solo si el downloader ya ha empezado (transfer en IN_PROGRESS)
        if (transfer.getStatus() != Transfer.Status.IN_PROGRESS) {
            throw new ApiException(ApiErrorCode.TRANSFER_STATE_CONFLICT,
                    Map.of("action", "upload", "expected", Transfer.Status.IN_PROGRESS.name(),
                            "status", transfer.getStatus().name()));
        }

        if (!transfer.getSender().getId().equals(caller.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_ONLY_SENDER_MAY_UPLOAD);
        }
        if (!transfer.getSender().getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_SENDER_NOT_OWNED_BY_USER);
        }

        if (!presenceRepository.isOnline(transfer.getReceiver().getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "receiver"));
        }

        StreamingPipe pipe = pipeRegistry.getOrCreate(transferId);
        if (!pipe.claimUploader()) {
            throw new ApiException(ApiErrorCode.TRANSFER_UPLOAD_IN_PROGRESS);
        }

        CountingOutputStream counting = new CountingOutputStream(pipe.sink());

        try {
            pipe.publishMetadata(filename, contentType, declaredSize);

            ObjectNode uploadStartedData = JsonNodeFactory.instance.objectNode();
            uploadStartedData.put("transferId", transferId);
            if (filename != null) uploadStartedData.put("filename", filename);
            if (contentType != null) uploadStartedData.put("contentType", contentType);
            if (declaredSize != null) uploadStartedData.put("sizeBytes", declaredSize);
            notifyDevice(transfer.getReceiver().getId(), "transfer.upload-started", uploadStartedData);

            if (!pipe.awaitRendezvous(RENDEZVOUS_TIMEOUT_MS)) {
                pipe.abort(new RuntimeException("Downloader did not arrive"));
                markFailed(transfer, counting.getCount(), "Downloader did not connect");
                throw new ApiException(ApiErrorCode.TRANSFER_DOWNLOADER_TIMEOUT);
            }

            try (InputStream in = request.getInputStream()) {
                in.transferTo(counting);
            } finally {
                try { pipe.sink().close(); } catch (Exception ignored) {}
            }

            transfer.setSizeBytes(counting.getCount());
            transferRepository.save(transfer);
            return ResponseEntity.noContent().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pipe.abort(e);
            markFailed(transfer, counting.getCount(), "Interrupted");
            throw new ApiException(ApiErrorCode.TRANSFER_INTERRUPTED);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            pipe.abort(e);
            markFailed(transfer, counting.getCount(), e.getMessage());
            throw new ApiException(ApiErrorCode.TRANSFER_UPLOAD_FAILED, e.getMessage());
        } finally {
            pipe.release();
        }
    }

    @GetMapping("/{transferId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long transferId,
                                                          @AuthenticationPrincipal UserDetails principal,
                                                          HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);
        Transfer transfer = loadPendingTransfer(transferId);

        if (!transfer.getReceiver().getId().equals(caller.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_ONLY_RECEIVER_MAY_DOWNLOAD);
        }
        if (!transfer.getReceiver().getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.TRANSFER_RECEIVER_NOT_OWNED_BY_USER);
        }

        if (!presenceRepository.isOnline(transfer.getSender().getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, Map.of("role", "sender"));
        }

        BillingPlan plan = user.getBillingPlan();
        if (plan == null) {
            throw new ApiException(ApiErrorCode.TRANSFER_MISSING_PLAN);
        }
        int maxConcurrent = plan.getMaxConcurrentTransfers();
        if (maxConcurrent > 0) {
            long inProgress = transferRepository
                    .countByOwnerUserAndStatus(user, Transfer.Status.IN_PROGRESS);
            if (inProgress >= maxConcurrent) {
                throw new ApiException(ApiErrorCode.TRANSFER_CONCURRENT_LIMIT,
                        Map.of("maxConcurrent", maxConcurrent));
            }
        }

        transfer.setStatus(Transfer.Status.IN_PROGRESS);
        transfer.setStartedAt(ZonedDateTime.now());
        transferRepository.save(transfer);
        eventBroadcaster.transferUpdated(user.getId(), transfer.getId(), transfer.getStatus());

        long throttleBytesPerSecond = resolveDownloadRate(user);

        StreamingPipe pipe = pipeRegistry.getOrCreate(transferId);
        if (!pipe.claimDownloader()) {
            throw new ApiException(ApiErrorCode.TRANSFER_DOWNLOAD_IN_PROGRESS);
        }

        ObjectNode downloadStartedData = JsonNodeFactory.instance.objectNode();
        downloadStartedData.put("transferId", transferId);
        notifyDevice(transfer.getSender().getId(), "transfer.download-started", downloadStartedData);

        StreamingPipe.Metadata meta;
        try {
            meta = pipe.awaitMetadata(METADATA_TIMEOUT_MS);
        } catch (UploaderDenialException denied) {
            markFailed(transfer, 0L,
                    "DENIED:" + denied.getCode().name()
                            + (denied.getMessage() == null ? "" : ":" + denied.getMessage()));
            pipe.release();
            ApiErrorCode code = switch (denied.getCode()) {
                case FILE_NOT_FOUND -> ApiErrorCode.TRANSFER_SENDER_DENIED_FILE_NOT_FOUND;
                case FOLDER_NOT_SHARED -> ApiErrorCode.TRANSFER_SENDER_DENIED_FOLDER_NOT_SHARED;
                case ACCESS_DENIED -> ApiErrorCode.TRANSFER_SENDER_DENIED_ACCESS_DENIED;
                case FILE_TOO_LARGE -> ApiErrorCode.TRANSFER_SENDER_DENIED_FILE_TOO_LARGE;
                case OTHER -> ApiErrorCode.TRANSFER_SENDER_DENIED_OTHER;
            };
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("senderCode", denied.getCode().name());
            if (denied.getMessage() != null) details.put("senderMessage", denied.getMessage());
            throw new ApiException(code, details);
        } catch (TimeoutException e) {
            pipe.abort(e);
            markFailed(transfer, 0L, "Uploader did not start");
            pipe.release();
            throw new ApiException(ApiErrorCode.TRANSFER_UPLOADER_TIMEOUT);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(meta.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(meta.filename(), StandardCharsets.UTF_8).build());
        if (meta.size() != null && meta.size() >= 0) {
            headers.setContentLength(meta.size());
        }

        StreamingResponseBody body = out -> {
            OutputStream throttled = throttleBytesPerSecond > 0
                    ? new ThrottledOutputStream(out, throttleBytesPerSecond)
                    : out;
            CountingOutputStream counting = new CountingOutputStream(throttled);
            try (InputStream source = pipe.source()) {
                source.transferTo(counting);
                counting.flush();
                markCompleted(transfer);
            } catch (Exception e) {
                pipe.abort(e);
                markFailed(transfer, counting.getCount(), e.getMessage());
                throw e;
            } finally {
                pipe.release();
            }
        };

        return ResponseEntity.ok().headers(headers).body(body);
    }

    private long resolveDownloadRate(User user) {
        BillingPlan plan = user.getBillingPlan();
        long planBytesPerSec = 0L;
        if (plan != null && plan.getMaxSpeed() > 0) {
            planBytesPerSec = (long) plan.getMaxSpeed() * BYTES_PER_MBPS;
        }
        if (planBytesPerSec == 0) return serverCeilingBytesPerSecond;
        if (serverCeilingBytesPerSecond == 0) return planBytesPerSec;
        return Math.min(planBytesPerSec, serverCeilingBytesPerSecond);
    }

    // ----- helpers -----

    private void notifyDevice(Long deviceId, String op, ObjectNode data) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("op", op);
        envelope.set("data", data);
        messagingTemplate.convertAndSend("/queue/device." + deviceId, envelope);
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ApiException(ApiErrorCode.USER_NOT_FOUND));
    }

    private Device currentDevice(HttpServletRequest request, User user) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.DEVICE_ID_ATTR);
        if (!(attr instanceof Long deviceId)) {
            throw new ApiException(ApiErrorCode.DEVICE_TOKEN_MISSING);
        }
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.DEVICE_TOKEN_UNKNOWN));
        if (!device.getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_NOT_OWNED_BY_USER);
        }
        if (device.getStatus() != Device.Status.ACTIVE) {
            throw new ApiException(ApiErrorCode.DEVICE_NOT_ACTIVE);
        }
        return device;
    }

    private Transfer loadPendingTransfer(Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.TRANSFER_NOT_FOUND));
        if (transfer.getStatus() != Transfer.Status.PENDING) {
            throw new ApiException(ApiErrorCode.TRANSFER_STATE_CONFLICT,
                    Map.of("action", "download", "expected", Transfer.Status.PENDING.name(),
                            "status", transfer.getStatus().name()));
        }
        return transfer;
    }

    private void markCompleted(Transfer transfer) {
        transfer.setStatus(Transfer.Status.COMPLETED);
        transfer.setCompletedAt(ZonedDateTime.now());
        transferRepository.save(transfer);
        eventBroadcaster.transferUpdated(transfer.getOwner().getUser().getId(),
                transfer.getId(), transfer.getStatus());
    }

    private Map<String, Object> idempotentResponse(Device owner, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        var existing = transferRepository.findByOwnerAndIdempotencyKey(owner, idempotencyKey);
        return existing.map(t -> Map.<String, Object>of(
                "transferId", t.getId(),
                "idempotent", true)).orElse(null);
    }

    private long requireNonNegativeSize(JsonNode body) {
        long size = requireLong(body, "sizeBytes");
        if (size < 0) {
            throw new ApiException(ApiErrorCode.TRANSFER_SIZE_NEGATIVE);
        }
        return size;
    }

    private Device loadPeerDevice(Long deviceId, User user) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.DEVICE_NOT_FOUND));
        if (!device.getUser().getId().equals(user.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_NOT_OWNED_BY_USER);
        }
        if (device.getStatus() != Device.Status.ACTIVE) {
            throw new ApiException(ApiErrorCode.DEVICE_NOT_ACTIVE);
        }
        return device;
    }

    private Folder loadFolderWithPermission(Long folderId, Device ownerDevice, Folder.Sharing required) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.FOLDER_NOT_FOUND));
        if (!folder.getDevice().getId().equals(ownerDevice.getId())) {
            throw new ApiException(ApiErrorCode.FOLDER_NOT_OWNED_BY_DEVICE);
        }
        if (!folder.isEnabled()) {
            throw new ApiException(ApiErrorCode.FOLDER_DISABLED);
        }
        Folder.Sharing current = folder.getSharing();
        if (current == null || !current.allows(required)) {
            throw new ApiException(ApiErrorCode.FOLDER_SHARING_INSUFFICIENT,
                    Map.of("required", required.name(),
                            "current", current == null ? "null" : current.name()));
        }
        return folder;
    }

    private void checkMonthlyQuota(User user, long fileSize) {
        BillingPlan plan = user.getBillingPlan();
        if (plan == null) {
            throw new ApiException(ApiErrorCode.TRANSFER_MISSING_PLAN);
        }
        long quotaBytes = (long) plan.getMaxTraffic() * BYTES_PER_MB;
        if (quotaBytes <= 0) return;
        long usedBytes = transferRepository.sumSizeBytesSince(user, ZonedDateTime.now().minusMonths(1));
        if (usedBytes + fileSize > quotaBytes) {
            throw new ApiException(ApiErrorCode.TRANSFER_MONTHLY_QUOTA_EXCEEDED,
                    Map.of("usedBytes", usedBytes, "fileBytes", fileSize, "quotaBytes", quotaBytes));
        }
    }

    private Transfer newPendingTransfer(Device sender, Device receiver, Device owner,
                                        String originPath, String destinationPath,
                                        String idempotencyKey) {
        Transfer transfer = new Transfer();
        transfer.setStatus(Transfer.Status.PENDING);
        transfer.setSender(sender);
        transfer.setReceiver(receiver);
        transfer.setOwner(owner);
        transfer.setOriginPath(originPath);
        transfer.setDestinationPath(destinationPath);
        transfer.setSizeBytes(0L);
        transfer.setCreatedAt(ZonedDateTime.now());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            transfer.setIdempotencyKey(idempotencyKey);
        }
        return transfer;
    }

    private void markFailed(Transfer transfer, long bytesTransferred, String reason) {
        try {
            transfer.setStatus(Transfer.Status.FAILED);
            transfer.setSizeBytes(bytesTransferred);
            transfer.setCompletedAt(ZonedDateTime.now());
            if (reason != null && !reason.isBlank()) {
                String trimmed = reason.length() > 500 ? reason.substring(0, 500) : reason;
                transfer.setFailureReason(trimmed);
            }
            transferRepository.save(transfer);
            eventBroadcaster.transferUpdated(transfer.getOwner().getUser().getId(),
                    transfer.getId(), transfer.getStatus());
        } catch (Exception ignored) {}
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

    private static Long requireLong(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) {
            throw ApiException.of(ApiErrorCode.MISSING_FIELD, "field", field);
        }
        if (node.isNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); }
            catch (NumberFormatException e) {
                throw ApiException.of(ApiErrorCode.INVALID_FIELD, "field", field);
            }
        }
        throw ApiException.of(ApiErrorCode.INVALID_FIELD, "field", field);
    }
}
