package baultServer.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excepción que transporta un {@link ApiErrorCode} hasta el {@code GlobalApiExceptionHandler},
 * donde se traduce a {@link ApiErrorResponse} con el HTTP status del código.
 *
 * Los {@code details} son datos estructurados que el cliente puede usar para renderizar
 * mensajes traducidos (p. ej. {@code retryAfterSeconds}, {@code required}, {@code current}).
 * NUNCA metas aquí texto ya localizado — la traducción vive en el cliente.
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ApiErrorCode code) {
        this(code, code.defaultMessage(), null, null);
    }

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ApiException(ApiErrorCode code, Map<String, Object> details) {
        this(code, code.defaultMessage(), details, null);
    }

    public ApiException(ApiErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ApiException(ApiErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message == null ? code.defaultMessage() : message, cause);
        this.code = code;
        this.details = details;
    }

    public ApiErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException of(ApiErrorCode code, String field, Object value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(field, value);
        return new ApiException(code, d);
    }
}
