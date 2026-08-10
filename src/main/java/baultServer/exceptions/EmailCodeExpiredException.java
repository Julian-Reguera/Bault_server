package baultServer.exceptions;

/**
 * El código encontrado ya ha superado su expiresAt.
 * El controller la traduce a 410.
 */
public class EmailCodeExpiredException extends RuntimeException {
    public EmailCodeExpiredException() {
        super("Code expired");
    }
}
