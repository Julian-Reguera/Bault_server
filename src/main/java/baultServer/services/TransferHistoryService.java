package baultServer.services;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.Transfer;
import baultServer.model.User;
import baultServer.repositorys.FolderRepository;
import baultServer.repositorys.TransferRepository;
import jakarta.persistence.criteria.Predicate;

@Service
public class TransferHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final TransferRepository transferRepository;
    private final FolderRepository folderRepository;
    private final ObjectMapper objectMapper;
    private final EventWsBroadcaster eventBroadcaster;

    public TransferHistoryService(TransferRepository transferRepository,
                                  FolderRepository folderRepository,
                                  ObjectMapper objectMapper,
                                  EventWsBroadcaster eventBroadcaster) {
        this.transferRepository = transferRepository;
        this.folderRepository = folderRepository;
        this.objectMapper = objectMapper;
        this.eventBroadcaster = eventBroadcaster;
    }

    // ---------------- Listado paginado ----------------

    public HistoryPage list(User user, HistoryFilter filter, String cursor, Integer requestedSize) {
        int size = clampSize(requestedSize);
        Cursor c = decodeCursor(cursor);
        Specification<Transfer> spec = buildSpec(user, filter, c);

        // Pedimos size+1 para saber si hay mas.
        PageRequest pageRequest = PageRequest.of(0, size + 1,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Transfer> page = transferRepository.findAll(spec, pageRequest);

        List<Transfer> content = page.getContent();
        boolean hasMore = content.size() > size;
        List<Transfer> pageItems = hasMore ? content.subList(0, size) : content;

        String nextCursor = null;
        if (hasMore) {
            Transfer last = pageItems.get(pageItems.size() - 1);
            nextCursor = encodeCursor(last.getCreatedAt(), last.getId());
        }

        List<HistoryItem> items = pageItems.stream().map(HistoryItem::of).toList();
        HistoryFilters filters = buildFilters(user);
        return new HistoryPage(items, nextCursor, filters);
    }

    public HistoryFilters buildFilters(User user) {
        List<DeviceRef> senders = transferRepository.distinctSendersForUser(user).stream()
                .map(DeviceRef::of).toList();
        List<DeviceRef> receivers = transferRepository.distinctReceiversForUser(user).stream()
                .map(DeviceRef::of).toList();
        return new HistoryFilters(senders, receivers);
    }

    // ---------------- Retry ----------------

    /**
     * Crea una nueva Transfer PENDING reutilizando los mismos parametros que la original,
     * previa validacion estricta:
     * <ul>
     *   <li>El caller debe ser el owner de la transferencia original.</li>
     *   <li>La original debe estar en un estado no-en-curso: FAILED, DENIED o CANCELLED.</li>
     *   <li>Los devices sender y receiver siguen existiendo y estan ACTIVE.</li>
     *   <li>La folder implicada (origin en download-request, destination en upload-request)
     *       sigue existiendo, enabled y con el nivel de sharing minimo requerido.</li>
     * </ul>
     */
    @Transactional
    public Transfer retry(Long transferId, User user, Device callerDevice) {
        Transfer original = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));

        if (!original.getOwner().getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transfer does not belong to user");
        }
        if (!original.getOwner().getId().equals(callerDevice.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the original owner device may retry (owner=" + original.getOwner().getId() + ")");
        }

        Set<Transfer.Status> retryable = EnumSet.of(
                Transfer.Status.FAILED, Transfer.Status.DENIED, Transfer.Status.CANCELLED);
        if (!retryable.contains(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot retry transfer in status " + original.getStatus());
        }

        Device sender = original.getSender();
        Device receiver = original.getReceiver();
        if (sender == null || receiver == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original transfer missing sender or receiver");
        }
        requireActive(sender, "Sender");
        requireActive(receiver, "Receiver");

        boolean callerIsReceiver = original.getOwner().getId().equals(receiver.getId());
        Folder folderToCheck = callerIsReceiver ? original.getOriginFolder() : original.getDestinationFolder();
        Folder.Sharing requiredSharing = callerIsReceiver ? Folder.Sharing.READ : Folder.Sharing.READ_WRITE;
        if (folderToCheck == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Original transfer has no folder to re-validate");
        }
        Folder freshFolder = folderRepository.findById(folderToCheck.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Folder no longer exists"));
        if (!freshFolder.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Folder is no longer enabled");
        }
        Folder.Sharing current = freshFolder.getSharing();
        if (current == null || !current.allows(requiredSharing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Folder sharing changed (required=" + requiredSharing + ", current=" + current + ")");
        }
        Device folderOwnerDevice = freshFolder.getDevice();
        Device expectedOwnerDevice = callerIsReceiver ? sender : receiver;
        if (!folderOwnerDevice.getId().equals(expectedOwnerDevice.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Folder no longer owned by expected device");
        }

        Transfer copy = new Transfer();
        copy.setStatus(Transfer.Status.PENDING);
        copy.setSender(sender);
        copy.setReceiver(receiver);
        copy.setOwner(callerDevice);
        copy.setOriginPath(original.getOriginPath());
        copy.setDestinationPath(original.getDestinationPath());
        copy.setSizeBytes(0L);
        copy.setCreatedAt(ZonedDateTime.now());
        if (callerIsReceiver) {
            copy.setOriginFolder(freshFolder);
        } else {
            copy.setDestinationFolder(freshFolder);
        }
        Transfer saved = transferRepository.save(copy);
        eventBroadcaster.transferCreated(user.getId(), saved.getId(), saved.getStatus());
        return saved;
    }

    // ---------------- CSV streaming ----------------

    public void streamCsv(User user, HistoryFilter filter, Writer writer) throws IOException {
        writer.write("fecha_iso,archivo,emisor,receptor,ruta_origen,ruta_destino,tamano_bytes,cifrado,estado,motivo_fallo\n");

        Specification<Transfer> baseSpec = buildSpec(user, filter, null);
        int pageSize = 500;
        Cursor cursor = null;
        while (true) {
            Specification<Transfer> spec = cursor == null ? baseSpec : baseSpec.and(cursorPredicate(cursor));
            PageRequest pr = PageRequest.of(0, pageSize,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
            Page<Transfer> page = transferRepository.findAll(spec, pr);
            if (page.isEmpty()) break;
            for (Transfer t : page.getContent()) {
                writeCsvRow(writer, t);
            }
            if (page.getContent().size() < pageSize) break;
            Transfer last = page.getContent().get(page.getContent().size() - 1);
            cursor = new Cursor(last.getCreatedAt(), last.getId());
        }
        writer.flush();
    }

    private void writeCsvRow(Writer w, Transfer t) throws IOException {
        w.write(t.getCreatedAt() == null ? "" : t.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        w.write(",");
        w.write(csv(fileName(t.getOriginPath())));
        w.write(",");
        w.write(csv(t.getSender() == null ? "" : t.getSender().getAlias()));
        w.write(",");
        w.write(csv(t.getReceiver() == null ? "" : t.getReceiver().getAlias()));
        w.write(",");
        w.write(csv(t.getOriginPath()));
        w.write(",");
        w.write(csv(t.getDestinationPath()));
        w.write(",");
        w.write(t.getSizeBytes() == null ? "0" : t.getSizeBytes().toString());
        w.write(",");
        w.write(isEncrypted(t) ? "true" : "false");
        w.write(",");
        w.write(t.getStatus().name());
        w.write(",");
        w.write(csv(t.getFailureReason()));
        w.write("\n");
    }

    private static String csv(String value) {
        if (value == null) return "";
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String s = value.replace("\"", "\"\"");
        return quote ? "\"" + s + "\"" : s;
    }

    // ---------------- Specs ----------------

    private Specification<Transfer> buildSpec(User user, HistoryFilter filter, Cursor cursor) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("owner").get("user"), user));

            Set<Transfer.Status> statuses = statusesFor(filter.status());
            if (statuses != null) {
                where.add(root.get("status").in(statuses));
            }
            if (filter.deviceId() != null) {
                where.add(cb.or(
                        cb.equal(root.get("sender").get("id"), filter.deviceId()),
                        cb.equal(root.get("receiver").get("id"), filter.deviceId())));
            }
            if (filter.senderId() != null) {
                where.add(cb.equal(root.get("sender").get("id"), filter.senderId()));
            }
            if (filter.receiverId() != null) {
                where.add(cb.equal(root.get("receiver").get("id"), filter.receiverId()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String like = "%" + filter.q().toLowerCase() + "%";
                where.add(cb.like(cb.lower(root.get("originPath")), like));
            }
            if (cursor != null) {
                where.add(cb.or(
                        cb.lessThan(root.get("createdAt"), cursor.createdAt()),
                        cb.and(cb.equal(root.get("createdAt"), cursor.createdAt()),
                                cb.lessThan(root.get("id"), cursor.id()))));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    private Specification<Transfer> cursorPredicate(Cursor cursor) {
        return (root, query, cb) -> cb.or(
                cb.lessThan(root.get("createdAt"), cursor.createdAt()),
                cb.and(cb.equal(root.get("createdAt"), cursor.createdAt()),
                        cb.lessThan(root.get("id"), cursor.id())));
    }

    private Set<Transfer.Status> statusesFor(StatusFilter s) {
        return switch (s) {
            case ALL -> null;
            case OK -> EnumSet.of(Transfer.Status.COMPLETED);
            case ACTIVE -> EnumSet.of(Transfer.Status.PENDING, Transfer.Status.IN_PROGRESS);
            case FAILED -> EnumSet.of(Transfer.Status.FAILED, Transfer.Status.DENIED, Transfer.Status.CANCELLED);
        };
    }

    // ---------------- Helpers ----------------

    private void requireActive(Device device, String role) {
        if (device.getStatus() != Device.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    role + " device is not ACTIVE (status=" + device.getStatus() + ")");
        }
    }

    private static int clampSize(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private String encodeCursor(ZonedDateTime createdAt, Long id) {
        try {
            String json = objectMapper.writeValueAsString(
                    new CursorPayload(createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), id));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot encode cursor", e);
        }
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            JsonNode node = objectMapper.readTree(raw);
            String ts = node.get("t").asText();
            long id = node.get("i").asLong();
            return new Cursor(ZonedDateTime.parse(ts), id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }

    static String fileName(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    static boolean isEncrypted(Transfer t) {
        Folder origin = t.getOriginFolder();
        if (origin != null && origin.getWrappedDek() != null) return true;
        Folder dest = t.getDestinationFolder();
        return dest != null && dest.getWrappedDek() != null;
    }

    // ---------------- DTOs / records ----------------

    public enum StatusFilter { ALL, OK, ACTIVE, FAILED }

    public record HistoryFilter(StatusFilter status, Long deviceId, Long senderId, Long receiverId, String q) {}

    public record Cursor(ZonedDateTime createdAt, Long id) {}

    private record CursorPayload(String t, Long i) {}

    public record DeviceRef(Long id, String alias) {
        public static DeviceRef of(Device d) {
            return new DeviceRef(d.getId(), d.getAlias());
        }
    }

    public record HistoryFilters(List<DeviceRef> senders, List<DeviceRef> receivers) {}

    public record HistoryItem(
            Long id,
            Transfer.Status status,
            String fileName,
            DeviceRef owner,
            DeviceRef sender,
            DeviceRef receiver,
            String originPath,
            String destinationPath,
            Long sizeBytes,
            boolean encrypted,
            String encryptionAlgorithm,
            ZonedDateTime createdAt,
            ZonedDateTime startedAt,
            ZonedDateTime completedAt,
            String failureReason) {
        public static HistoryItem of(Transfer t) {
            String algo = null;
            Folder origin = t.getOriginFolder();
            Folder dest = t.getDestinationFolder();
            if (origin != null && origin.getEncryptionAlgorithm() != null) algo = origin.getEncryptionAlgorithm();
            else if (dest != null && dest.getEncryptionAlgorithm() != null) algo = dest.getEncryptionAlgorithm();
            return new HistoryItem(
                    t.getId(),
                    t.getStatus(),
                    TransferHistoryService.fileName(t.getOriginPath()),
                    DeviceRef.of(t.getOwner()),
                    t.getSender() == null ? null : DeviceRef.of(t.getSender()),
                    t.getReceiver() == null ? null : DeviceRef.of(t.getReceiver()),
                    t.getOriginPath(),
                    t.getDestinationPath(),
                    t.getSizeBytes(),
                    isEncrypted(t),
                    algo,
                    t.getCreatedAt(),
                    t.getStartedAt(),
                    t.getCompletedAt(),
                    t.getFailureReason());
        }
    }

    public record HistoryPage(List<HistoryItem> items, String nextCursor, HistoryFilters filters) {}
}
