package baultServer.exceptions;

/**
 * Se lanza cuando un login intenta verificar un device marcado como REMOVED.
 * El controller lo captura y cae al camino de register() para crear un device nuevo.
 */
public class DeviceRemovedException extends RuntimeException {
    public DeviceRemovedException() {
        super("Device removed");
    }
}
