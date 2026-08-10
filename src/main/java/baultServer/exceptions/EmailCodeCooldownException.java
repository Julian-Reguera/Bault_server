package baultServer.exceptions;

/**
 * Se lanza cuando se pide un nuevo código antes de que expire el cooldown del anterior.
 * El controller la traduce a 429.
 */
public class EmailCodeCooldownException extends RuntimeException {
    private final long retryAfterSeconds;

    public EmailCodeCooldownException(long retryAfterSeconds) {
        super("Wait " + retryAfterSeconds + "s before requesting another code");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
