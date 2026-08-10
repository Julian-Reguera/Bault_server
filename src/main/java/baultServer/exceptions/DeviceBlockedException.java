package baultServer.exceptions;

/**
 * El device existe y el secret es correcto, pero está en status BLOCKED.
 * El controller la traduce a 403.
 */
public class DeviceBlockedException extends RuntimeException {
    public DeviceBlockedException() {
        super("Device blocked");
    }
}
