package baultServer.exceptions;

/**
 * Falla la autenticación del device: o el par (user, deviceId) no existe o el secret no cuadra.
 * El controller la traduce a 401 sin distinguir ambos casos hacia fuera si así lo decide.
 */
public class DeviceAuthenticationException extends RuntimeException {
    public DeviceAuthenticationException(String message) {
        super(message);
    }
}
