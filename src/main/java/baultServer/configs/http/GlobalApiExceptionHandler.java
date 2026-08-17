package baultServer.configs.http;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiErrorResponse;
import baultServer.exceptions.ApiException;

/**
 * Traduce cualquier excepción que salga de un controller a la forma canónica
 * {@link ApiErrorResponse} = {@code {code, message, details?}}.
 *
 * Nota: los 401 por token ausente/inválido en endpoints protegidos NO pasan por aquí
 * (los emite {@code JsonAuthenticationEntryPoint} en Spring Security antes de llegar
 * a MVC). Cambia ambos juntos si quieres modificar el formato.
 */
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex) {
        ApiErrorCode code = ex.code();
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code, ex.getMessage(), ex.details()));
    }

    // --- Spring Security ---

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return respond(ApiErrorCode.INVALID_CREDENTIALS, ex.getMessage(), null);
    }

    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<ApiErrorResponse> handleDisabled(AuthenticationException ex) {
        return respond(ApiErrorCode.USER_DISABLED, ex.getMessage(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuth(AuthenticationException ex) {
        return respond(ApiErrorCode.UNAUTHENTICATED, ex.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return respond(ApiErrorCode.ACCESS_DENIED, ex.getMessage(), null);
    }

    // --- Legacy / bordes ---

    /**
     * Última red de seguridad para código que todavía lance {@code ResponseStatusException}
     * directamente. El status HTTP se respeta; el {@code code} se degrada a genérico.
     * El objetivo del refactor es que este handler no se dispare en producción.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleRse(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ApiErrorCode fallback = status.is4xxClientError()
                ? ApiErrorCode.INVALID_FIELD
                : ApiErrorCode.INTERNAL_ERROR;
        log.warn("Unmapped ResponseStatusException {} — should be migrated to ApiException", status, ex);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status.value());
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(fallback, ex.getReason(), details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(Exception ex) {
        log.error("Unhandled exception reaching advice", ex);
        return respond(ApiErrorCode.INTERNAL_ERROR, ApiErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private static ResponseEntity<ApiErrorResponse> respond(ApiErrorCode code, String message,
                                                            Map<String, Object> details) {
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code, message, details));
    }
}
