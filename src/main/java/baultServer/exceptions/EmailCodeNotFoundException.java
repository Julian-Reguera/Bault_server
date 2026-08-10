package baultServer.exceptions;

/**
 * No hay ningún código activo (no consumido) para el (user, purpose) indicado.
 * El controller la traduce a 400.
 */
public class EmailCodeNotFoundException extends RuntimeException {
    public EmailCodeNotFoundException() {
        super("No active code");
    }
}
