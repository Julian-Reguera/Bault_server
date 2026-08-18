package baultServer.exceptions;

import org.springframework.http.HttpStatus;

/**
 * Catálogo cerrado de códigos de error que la API puede devolver.
 * El cliente traduce por {@code code}; el {@code message} en la respuesta HTTP es
 * solo un fallback en inglés pensado para logs y debugging.
 *
 * IMPORTANTE: cualquier nuevo error que se añada al servidor debe entrar aquí
 * y en la sección "API error codes" del README. Los codes son parte del contrato
 * público y no deben renombrarse una vez publicados.
 */
public enum ApiErrorCode {

    // ---------- Genéricos ----------
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    MISSING_FIELD(HttpStatus.BAD_REQUEST, "Missing required field"),
    INVALID_FIELD(HttpStatus.BAD_REQUEST, "Invalid field value"),

    // ---------- Auth ----------
    EMAIL_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid email format"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email not verified"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    USER_DISABLED(HttpStatus.FORBIDDEN, "User disabled"),
    LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts"),

    // ---------- Refresh tokens ----------
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token expired"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),

    // ---------- Email codes ----------
    EMAIL_CODE_NOT_FOUND(HttpStatus.BAD_REQUEST, "No active email code"),
    EMAIL_CODE_INVALID(HttpStatus.BAD_REQUEST, "Invalid email code"),
    EMAIL_CODE_EXPIRED(HttpStatus.GONE, "Email code expired"),
    EMAIL_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "Email code cooldown active"),

    // ---------- Devices ----------
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Device not found"),
    DEVICE_INVALID_SECRET(HttpStatus.UNAUTHORIZED, "Invalid device credentials"),
    DEVICE_BLOCKED(HttpStatus.FORBIDDEN, "Device blocked"),
    DEVICE_NOT_ACTIVE(HttpStatus.FORBIDDEN, "Device not active"),
    DEVICE_NOT_OWNED_BY_USER(HttpStatus.FORBIDDEN, "Device does not belong to user"),
    DEVICE_OFFLINE(HttpStatus.SERVICE_UNAVAILABLE, "Device offline"),
    DEVICE_STATE_TRANSITION_INVALID(HttpStatus.CONFLICT, "Device state transition not allowed"),
    DEVICE_PLAN_LIMIT_REACHED(HttpStatus.CONFLICT, "Plan device limit reached"),
    DEVICE_DEACTIVATE_LOCKED(HttpStatus.CONFLICT, "Device deactivation locked"),
    DEVICE_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "Missing device in token"),
    DEVICE_TOKEN_UNKNOWN(HttpStatus.UNAUTHORIZED, "Unknown device in token"),

    // ---------- Device RPC (server-initiated calls) ----------
    DEVICE_RPC_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Owner device did not respond"),
    DEVICE_RPC_DENIED(HttpStatus.FORBIDDEN, "Owner device denied the request"),
    DEVICE_RPC_ERROR(HttpStatus.BAD_GATEWAY, "Owner device replied with error"),
    DEVICE_RPC_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while waiting for device reply"),

    // ---------- Folders ----------
    FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Folder not found"),
    FOLDER_DISABLED(HttpStatus.FORBIDDEN, "Folder disabled"),
    FOLDER_NOT_OWNED_BY_USER(HttpStatus.FORBIDDEN, "Folder does not belong to user"),
    FOLDER_NOT_OWNED_BY_DEVICE(HttpStatus.FORBIDDEN, "Folder does not belong to expected device"),
    FOLDER_SHARING_INSUFFICIENT(HttpStatus.FORBIDDEN, "Folder sharing level insufficient"),
    FOLDER_INVALID_SHARING(HttpStatus.BAD_REQUEST, "Invalid folder sharing value"),
    FOLDER_PATH_TRAVERSAL(HttpStatus.FORBIDDEN, "Path traversal not allowed"),

    // ---------- Transfers ----------
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfer not found"),
    TRANSFER_NOT_OWNED_BY_USER(HttpStatus.FORBIDDEN, "Transfer does not belong to user"),
    TRANSFER_STATE_CONFLICT(HttpStatus.CONFLICT, "Transfer is not in a valid state for this operation"),
    TRANSFER_PEERS_MUST_DIFFER(HttpStatus.BAD_REQUEST, "Sender and receiver must differ"),
    TRANSFER_OWNER_MUST_DIFFER_FROM_PEERS(HttpStatus.BAD_REQUEST, "For third-party transfers the owner device must differ from both sender and receiver"),
    TRANSFER_KEYS_FORBIDDEN(HttpStatus.FORBIDDEN, "Only sender or receiver may fetch keys"),
    TRANSFER_ONLY_PEER_MAY_DENY(HttpStatus.FORBIDDEN, "Only the requested peer device may deny"),
    TRANSFER_ONLY_SENDER_MAY_UPLOAD(HttpStatus.FORBIDDEN, "Only the sender device may upload"),
    TRANSFER_ONLY_RECEIVER_MAY_DOWNLOAD(HttpStatus.FORBIDDEN, "Only the receiver device may download"),
    TRANSFER_SENDER_NOT_OWNED_BY_USER(HttpStatus.FORBIDDEN, "Sender not owned by user"),
    TRANSFER_RECEIVER_NOT_OWNED_BY_USER(HttpStatus.FORBIDDEN, "Receiver not owned by user"),
    TRANSFER_UPLOAD_IN_PROGRESS(HttpStatus.CONFLICT, "Upload already in progress"),
    TRANSFER_DOWNLOAD_IN_PROGRESS(HttpStatus.CONFLICT, "Download already in progress"),
    TRANSFER_DOWNLOADER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Downloader did not connect"),
    TRANSFER_UPLOADER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Uploader did not start"),
    TRANSFER_UPLOAD_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Upload failed"),
    TRANSFER_INTERRUPTED(HttpStatus.SERVICE_UNAVAILABLE, "Transfer interrupted"),
    TRANSFER_MISSING_PLAN(HttpStatus.FORBIDDEN, "User has no billing plan"),
    TRANSFER_CONCURRENT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Concurrent transfer limit reached"),
    TRANSFER_MONTHLY_QUOTA_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE, "Monthly transfer quota exceeded"),
    TRANSFER_INVALID_STATUS_FILTER(HttpStatus.BAD_REQUEST, "Invalid status filter"),
    TRANSFER_INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Invalid pagination cursor"),
    TRANSFER_SIZE_NEGATIVE(HttpStatus.BAD_REQUEST, "sizeBytes must be >= 0"),
    TRANSFER_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "Transfer cannot be retried in its current status"),
    TRANSFER_RETRY_MISSING_PEERS(HttpStatus.CONFLICT, "Original transfer missing sender or receiver"),
    TRANSFER_RETRY_MISSING_FOLDER(HttpStatus.CONFLICT, "Original transfer has no folder to re-validate"),
    TRANSFER_RETRY_FOLDER_GONE(HttpStatus.CONFLICT, "Folder no longer exists"),
    TRANSFER_RETRY_FOLDER_DISABLED(HttpStatus.CONFLICT, "Folder is no longer enabled"),
    TRANSFER_RETRY_SHARING_CHANGED(HttpStatus.CONFLICT, "Folder sharing changed"),
    TRANSFER_RETRY_FOLDER_REASSIGNED(HttpStatus.CONFLICT, "Folder no longer owned by expected device"),
    TRANSFER_RETRY_ONLY_ORIGINAL_OWNER(HttpStatus.FORBIDDEN, "Only the original owner device may retry"),
    TRANSFER_RETRY_DEVICE_NOT_ACTIVE(HttpStatus.CONFLICT, "Device is not ACTIVE for retry"),

    // Envueltos por el downloader cuando el sender rechaza durante el pipe.
    TRANSFER_SENDER_DENIED_FILE_NOT_FOUND(HttpStatus.GONE, "Sender denied: file not found"),
    TRANSFER_SENDER_DENIED_FOLDER_NOT_SHARED(HttpStatus.FORBIDDEN, "Sender denied: folder not shared"),
    TRANSFER_SENDER_DENIED_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Sender denied: access denied"),
    TRANSFER_SENDER_DENIED_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Sender denied: file too large"),
    TRANSFER_SENDER_DENIED_OTHER(HttpStatus.FAILED_DEPENDENCY, "Sender denied");

    private final HttpStatus status;
    private final String defaultMessage;

    ApiErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
