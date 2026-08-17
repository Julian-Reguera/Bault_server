package baultServer.exceptions;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Forma canónica de todas las respuestas de error de la API. El cliente traduce por
 * {@code code}; {@code message} es un fallback en inglés para logs/debug. {@code details}
 * lleva parámetros estructurados (p. ej. {@code retryAfterSeconds}, {@code field}) que el
 * cliente puede interpolar en su traducción.
 */
@JsonInclude(Include.NON_NULL)
public record ApiErrorResponse(String code, String message, Map<String, Object> details) {

    public static ApiErrorResponse of(ApiErrorCode code) {
        return new ApiErrorResponse(code.name(), code.defaultMessage(), null);
    }

    public static ApiErrorResponse of(ApiErrorCode code, String message) {
        return new ApiErrorResponse(code.name(), message, null);
    }

    public static ApiErrorResponse of(ApiErrorCode code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(code.name(),
                message == null ? code.defaultMessage() : message,
                (details == null || details.isEmpty()) ? null : details);
    }
}
