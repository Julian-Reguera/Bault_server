package baultServer.exceptions;

/**
 * Excepcion que el sender lanza para rechazar una transferencia
 * antes o durante el upload, con un codigo de motivo estructurado.
 */
public class UploaderDenialException extends RuntimeException {

    public enum Code {
        FILE_NOT_FOUND,       // el archivo ya no existe en el sender
        FOLDER_NOT_SHARED,    // la carpeta se ha dejado de compartir
        ACCESS_DENIED,        // el usuario del sender ha rechazado
        FILE_TOO_LARGE,       // ha cambiado y ya no cabe
        OTHER
    }

    private final Code code;

    public UploaderDenialException(Code code, String message) {
        super(message == null ? code.name() : message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
