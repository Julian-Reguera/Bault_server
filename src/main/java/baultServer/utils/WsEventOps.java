package baultServer.utils;

/**
 * Constantes de los eventos emitidos por WebSocket al canal de usuario.
 * Todos los eventos tienen la forma {@code {"op": "...", "data": {...}}} y se
 * envían a {@code /user/{userId}/queue/events}: Spring hace fan-out a todas
 * las sesiones activas del mismo usuario, así que cualquier device conectado
 * recibe los cambios en tiempo real sin necesidad de refrescar.
 *
 * <p>El device que provoca el evento también lo recibe (fan-out a todo el usuario);
 * el cliente puede ignorar los suyos comparando el {@code deviceId} del payload
 * con el suyo propio.
 */
public final class WsEventOps {

    /** Cambia el estado online/offline de un device del usuario. */
    public static final String DEVICE_PRESENCE = "device.presence";
    /** Se ha registrado un device nuevo del usuario. */
    public static final String DEVICE_CREATED = "device.created";
    /** Se ha renombrado, activado, desactivado, bloqueado o desbloqueado un device. */
    public static final String DEVICE_UPDATED = "device.updated";
    /** Un device ha pasado a REMOVED (tombstone). */
    public static final String DEVICE_REMOVED = "device.removed";

    /** Se ha creado una carpeta compartida (o no) del usuario. */
    public static final String FOLDER_CREATED = "folder.created";
    /** Ha cambiado el nivel de sharing de una carpeta. */
    public static final String FOLDER_UPDATED = "folder.updated";
    /** Una carpeta ha sido borrada (soft-delete). */
    public static final String FOLDER_DELETED = "folder.deleted";

    /** Se ha creado una transferencia (fila PENDING). */
    public static final String TRANSFER_CREATED = "transfer.created";
    /** Ha cambiado el estado de una transferencia (DENIED, IN_PROGRESS, COMPLETED, ...). */
    public static final String TRANSFER_UPDATED = "transfer.updated";

    /**
     * Destino STOMP relativo al usuario donde se emiten todos los eventos.
     * Los clientes se suscriben a {@code /user/queue/events} — Spring resuelve
     * automáticamente el userId de la sesión.
     */
    public static final String USER_EVENTS_QUEUE = "/queue/events";

    private WsEventOps() {}
}
