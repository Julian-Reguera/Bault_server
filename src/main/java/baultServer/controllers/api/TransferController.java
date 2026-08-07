package baultServer.controllers.api;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import baultServer.configs.JwtAuthenticationFilter;
import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.Transfer;
import baultServer.model.User;
import baultServer.repositorys.DevicePresenceRepository;
import baultServer.repositorys.DeviceRepository;
import baultServer.repositorys.FolderRepository;
import baultServer.repositorys.TransferRepository;
import baultServer.repositorys.UserRepository;
import baultServer.exceptions.UploaderDenialException;
import baultServer.repositorys.TransferPipeRepository;
import baultServer.utils.streams.CountingOutputStream;
import baultServer.utils.streams.StreamingPipe;
import baultServer.utils.streams.ThrottledOutputStream;
import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

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
    /** Techo global opcional (Mbps); 0 = sin techo global, se respeta solo el plan. */
    private final long serverCeilingBytesPerSecond;

    public TransferController(UserRepository userRepository,
                              DeviceRepository deviceRepository,
                              FolderRepository folderRepository,
                              TransferRepository transferRepository,
                              DevicePresenceRepository presenceRepository,
                              TransferPipeRepository pipeRegistry,
                              SimpMessagingTemplate messagingTemplate,
                              @Value("${bault.transfer.download.max-mbps:0}") double downloadMaxMbps) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.folderRepository = folderRepository;
        this.transferRepository = transferRepository;
        this.presenceRepository = presenceRepository;
        this.pipeRegistry = pipeRegistry;
        this.messagingTemplate = messagingTemplate;
        this.serverCeilingBytesPerSecond = downloadMaxMbps <= 0 ? 0L
                : (long) (downloadMaxMbps * BYTES_PER_MBPS);
    }

    /**
     * El device actual (receiver) pide descargar un archivo que vive en una carpeta
     * compartida de otro device suyo (sender). El sender debe estar online.
     */
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

        Device sender = loadPeerDevice(senderDeviceId, user, "Sender");
        if (sender.getId().equals(receiver.getId())) {
            throw new ResponseStatusException(BAD_REQUEST, "Sender and receiver must differ");
        }

        Folder originFolder = loadSharedFolder(originFolderId, sender, "Origin folder");

        if (!presenceRepository.isOnline(sender.getId())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Sender device offline");
        }

        checkMonthlyQuota(user, fileSize);

        Transfer transfer = newPendingTransfer(sender, receiver, receiver, originPath, destinationPath, idempotencyKey);
        transfer.setOriginFolder(originFolder);
        transferRepository.save(transfer);

        //Notificar al sender (peer) via WS para que decida aceptar (POST /upload) o rechazar (POST /deny).
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

    /**
     * El device actual (sender) pide enviar un archivo suyo a otro device suyo (receiver),
     * dejandolo en una carpeta compartida del receiver. El receiver debe estar online.
     */
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

        Device receiver = loadPeerDevice(receiverDeviceId, user, "Receiver");
        if (sender.getId().equals(receiver.getId())) {
            throw new ResponseStatusException(BAD_REQUEST, "Sender and receiver must differ");
        }

        Folder destinationFolder = loadSharedFolder(destinationFolderId, receiver, "Destination folder");

        if (!presenceRepository.isOnline(receiver.getId())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Receiver device offline");
        }

        checkMonthlyQuota(user, fileSize);

        Transfer transfer = newPendingTransfer(sender, receiver, sender, originPath, destinationPath, idempotencyKey);
        transfer.setDestinationFolder(destinationFolder);
        transferRepository.save(transfer);

        //Notificar al receiver (peer) via WS para que decida aceptar (GET /download) o rechazar (POST /deny).
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

    /** Cancela una transferencia que aun no ha comenzado. Puede hacerlo cualquier device del usuario receiver. */
    @DeleteMapping("/{transferId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long transferId,
                                       @AuthenticationPrincipal UserDetails principal,
                                       HttpServletRequest request) {
        User user = currentUser(principal);
        currentDevice(request, user); //valida que el JWT trae un device valido y habilitado del usuario

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transfer not found"));

        if (!transfer.getOwner().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Transfer does not belong to user");
        }
        if (transfer.getStatus() != Transfer.Status.PENDING) {
            throw new ResponseStatusException(CONFLICT,
                    "Cannot cancel transfer in status " + transfer.getStatus());
        }

        transfer.setStatus(Transfer.Status.FAILED);
        transfer.setFailureReason("Cancelled by user");
        transferRepository.save(transfer);
        return ResponseEntity.noContent().build();
    }

    /**
     * El peer al que se le solicito la transferencia la rechaza. Solo puede llamarlo el
     * device peer (el sender en download-request, el receiver en upload-request), no el owner.
     * Si el otro extremo estaba esperando en el pipe, se le aborta para que reciba error.
     */
    @PostMapping("/{transferId}/deny")
    public ResponseEntity<Void> deny(@PathVariable Long transferId,
                                     @AuthenticationPrincipal UserDetails principal,
                                     HttpServletRequest request) {
        User user = currentUser(principal);
        Device caller = currentDevice(request, user);

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transfer not found"));

        //Debe pertenecer al mismo user (defense-in-depth; sender/receiver ya son del mismo user por creacion).
        if (!transfer.getOwner().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Transfer does not belong to user");
        }

        //El denier debe ser el peer (no el owner) y formar parte de la transaccion.
        Device peer = transfer.getOwner().getId().equals(transfer.getSender().getId())
                ? transfer.getReceiver() : transfer.getSender();
        if (!caller.getId().equals(peer.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the requested peer device may deny");
        }

        if (transfer.getStatus() != Transfer.Status.PENDING
                && transfer.getStatus() != Transfer.Status.IN_PROGRESS) {
            throw new ResponseStatusException(CONFLICT,
                    "Cannot deny transfer in status " + transfer.getStatus());
        }

        transfer.setStatus(Transfer.Status.DENIED);
        transfer.setFailureReason("Denied by peer");
        transferRepository.save(transfer);

        //Desbloquear al otro extremo si estaba esperando en el pipe (metadata o rendezvous).
        StreamingPipe pipe = pipeRegistry.get(transferId);
        if (pipe != null) {
            pipe.abort(new RuntimeException("Denied by peer"));
        }

        //Notificar al owner via WS.
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
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transfer not found"));
        //Upload solo si el downloader ya ha empezado (transfer en IN_PROGRESS)
        if (transfer.getStatus() != Transfer.Status.IN_PROGRESS) {
            throw new ResponseStatusException(CONFLICT,
                    "Upload requires transfer to be IN_PROGRESS (was " + transfer.getStatus() + ")");
        }

        if (!transfer.getSender().getId().equals(caller.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the sender device may upload");
        }
        if (!transfer.getSender().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Sender not owned by user");
        }

        if (!presenceRepository.isOnline(transfer.getReceiver().getId())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Receiver device offline");
        }

        StreamingPipe pipe = pipeRegistry.getOrCreate(transferId);
        if (!pipe.claimUploader()) {
            throw new ResponseStatusException(CONFLICT, "Upload already in progress");
        }

        CountingOutputStream counting = new CountingOutputStream(pipe.sink());

        try {
            pipe.publishMetadata(filename, contentType, declaredSize);

            if (!pipe.awaitRendezvous(RENDEZVOUS_TIMEOUT_MS)) {
                pipe.abort(new RuntimeException("Downloader did not arrive"));
                markFailed(transfer, counting.getCount(), "Downloader did not connect");
                throw new ResponseStatusException(GATEWAY_TIMEOUT, "Downloader did not connect");
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
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Interrupted");
        } catch (Exception e) {
            pipe.abort(e);
            markFailed(transfer, counting.getCount(), e.getMessage());
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Upload failed: " + e.getMessage());
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
            throw new ResponseStatusException(FORBIDDEN, "Only the receiver device may download");
        }
        if (!transfer.getReceiver().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Receiver not owned by user");
        }

        if (!presenceRepository.isOnline(transfer.getSender().getId())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Sender device offline");
        }

        //Limite de concurrencia por plan (0 = ilimitado). Se comprueba antes de mover a IN_PROGRESS.
        BillingPlan plan = user.getBillingPlan();
        if (plan == null) {
            throw new ResponseStatusException(FORBIDDEN, "User has no billing plan");
        }
        int maxConcurrent = plan.getMaxConcurrentTransfers();
        if (maxConcurrent > 0) {
            long inProgress = transferRepository
                    .countByOwnerUserAndStatus(user, Transfer.Status.IN_PROGRESS);
            if (inProgress >= maxConcurrent) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Concurrent transfer limit reached (" + maxConcurrent + ")");
            }
        }

        //Transicion PENDING -> IN_PROGRESS
        transfer.setStatus(Transfer.Status.IN_PROGRESS);
        transfer.setStartedAt(ZonedDateTime.now());
        transferRepository.save(transfer);

        long throttleBytesPerSecond = resolveDownloadRate(user);

        StreamingPipe pipe = pipeRegistry.getOrCreate(transferId);
        if (!pipe.claimDownloader()) {
            throw new ResponseStatusException(CONFLICT, "Download already in progress");
        }

        StreamingPipe.Metadata meta;
        try {
            meta = pipe.awaitMetadata(METADATA_TIMEOUT_MS);
        } catch (UploaderDenialException denied) {
            markFailed(transfer, 0L,
                    "DENIED:" + denied.getCode().name()
                            + (denied.getMessage() == null ? "" : ":" + denied.getMessage()));
            pipe.release();
            HttpStatus status = switch (denied.getCode()) {
                case FILE_NOT_FOUND -> HttpStatus.GONE;
                case FOLDER_NOT_SHARED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
                case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
                case OTHER -> HttpStatus.FAILED_DEPENDENCY;
            };
            throw new ResponseStatusException(status,
                    "Sender denied: " + denied.getCode()
                            + (denied.getMessage() == null ? "" : " (" + denied.getMessage() + ")"));
        } catch (TimeoutException e) {
            pipe.abort(e);
            markFailed(transfer, 0L, "Uploader did not start");
            pipe.release();
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "Uploader did not start");
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
                //Upload ya guardo sizeBytes al terminar; solo cambiamos estado.
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

    /**
     * Resuelve la velocidad de descarga aplicable a un usuario:
     * min(plan, techo servidor), tratando 0 como "sin limite" en cada lado.
     * Devuelve 0 si no hay ninguna limitacion.
     */
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

    /** Envia un mensaje STOMP al device destino con el envelope {op, data}. */
    private void notifyDevice(Long deviceId, String op, ObjectNode data) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("op", op);
        envelope.set("data", data);
        messagingTemplate.convertAndSend("/queue/device." + deviceId, envelope);
    }

    private User currentUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
    }

    private Device currentDevice(HttpServletRequest request, User user) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.DEVICE_ID_ATTR);
        if (!(attr instanceof Long deviceId)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing device in token");
        }
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Unknown device"));
        if (!device.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "Device does not belong to user");
        }
        if (!device.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, "Device disabled");
        }
        return device;
    }

    private Transfer loadPendingTransfer(Long id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transfer not found"));
        if (transfer.getStatus() != Transfer.Status.PENDING) {
            throw new ResponseStatusException(CONFLICT, "Transfer not pending (status=" + transfer.getStatus() + ")");
        }
        return transfer;
    }

    private void markCompleted(Transfer transfer) {
        transfer.setStatus(Transfer.Status.COMPLETED);
        transferRepository.save(transfer);
    }

    /** Devuelve la respuesta idempotente si ya existia una transferencia con esa key para el owner. */
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
            throw new ResponseStatusException(BAD_REQUEST, "sizeBytes must be >= 0");
        }
        return size;
    }

    /** Carga un device peer y valida ownership y enabled. {@code role} solo para mensajes. */
    private Device loadPeerDevice(Long deviceId, User user, String role) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, role + " device not found"));
        if (!device.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(FORBIDDEN, role + " device does not belong to user");
        }
        if (!device.isEnabled()) {
            throw new ResponseStatusException(FORBIDDEN, role + " device disabled");
        }
        return device;
    }

    /** Carga una folder y valida que pertenece a {@code ownerDevice}, esta enabled y shared. */
    private Folder loadSharedFolder(Long folderId, Device ownerDevice, String role) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, role + " not found"));
        if (!folder.getDevice().getId().equals(ownerDevice.getId())) {
            throw new ResponseStatusException(FORBIDDEN, role + " does not belong to the expected device");
        }
        if (!folder.isEnabled() || !folder.isShared()) {
            throw new ResponseStatusException(FORBIDDEN, role + " not shared");
        }
        return folder;
    }

    /** Aplica el limite mensual de trafico del plan (0 = ilimitado). */
    private void checkMonthlyQuota(User user, long fileSize) {
        BillingPlan plan = user.getBillingPlan();
        if (plan == null) {
            throw new ResponseStatusException(FORBIDDEN, "User has no billing plan");
        }
        long quotaBytes = (long) plan.getMaxTraffic() * BYTES_PER_MB;
        if (quotaBytes <= 0) return;
        long usedBytes = transferRepository.sumSizeBytesSince(user, ZonedDateTime.now().minusMonths(1));
        if (usedBytes + fileSize > quotaBytes) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Monthly quota exceeded (used=" + usedBytes + " + file=" + fileSize
                            + " > quota=" + quotaBytes + ")");
        }
    }

    /** Crea la esqueleto de Transfer PENDING sin folders (las setea el caller). */
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
            if (reason != null && !reason.isBlank()) {
                String trimmed = reason.length() > 500 ? reason.substring(0, 500) : reason;
                transfer.setFailureReason(trimmed);
            }
            transferRepository.save(transfer);
        } catch (Exception ignored) {}
    }

    private static String requireText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing or invalid field: " + field);
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
            throw new ResponseStatusException(BAD_REQUEST, "Missing field: " + field);
        }
        if (node.isNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); }
            catch (NumberFormatException e) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid number: " + field);
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "Invalid field: " + field);
    }
}
