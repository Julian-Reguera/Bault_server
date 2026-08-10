package baultServer.exceptions;

/**
 * El código presentado no coincide con el almacenado (hash distinto).
 * El controller la traduce a 400.
 */
public class EmailCodeInvalidException extends RuntimeException {
    public EmailCodeInvalidException() {
        super("Invalid code");
    }
}
