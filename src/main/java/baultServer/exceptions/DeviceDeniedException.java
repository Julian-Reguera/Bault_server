package baultServer.exceptions;

public class DeviceDeniedException extends RuntimeException {
    public DeviceDeniedException(String reason) {
        super(reason);
    }
}
