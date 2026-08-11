package baultServer.repositorys;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * Registro en memoria de la sesión WebSocket activa por dispositivo.
 * Un dispositivo sólo puede tener UNA sesión suscrita al canal RPC a la vez:
 * un segundo intento de reclamar el canal se rechaza. El heartbeat garantiza
 * la liberación de sesiones muertas en pocos segundos.
 * <p>
 * Los mapas están indexados en ambos sentidos (deviceId → sessionId y
 * sessionId → deviceId) para que la liberación en el listener de disconnect
 * pueda hacerse con sólo el sessionId, sin extraer atributos del evento.
 */
@Repository
public class DevicePresenceRepository {

    private final ConcurrentHashMap<Long, String> sessionByDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> deviceBySession = new ConcurrentHashMap<>();

    /**
     * Intenta reclamar el canal RPC del dispositivo para la sesión dada.
     * Devuelve true si el reclamo tuvo éxito (device pasa a online).
     * Devuelve false si ya había otra sesión distinta reclamada — el caller
     * debe rechazar la suscripción; el heartbeat liberará la sesión anterior
     * si estaba muerta.
     * Reclamar dos veces con el mismo sessionId es idempotente y devuelve true.
     */
    public boolean tryClaim(Long deviceId, String sessionId) {
        String previous = sessionByDevice.putIfAbsent(deviceId, sessionId);
        if (previous == null) {
            deviceBySession.put(sessionId, deviceId);
            return true;
        }
        return previous.equals(sessionId);
    }

    /**
     * Libera la sesión dada. Marca el device asociado como offline.
     * Devuelve el deviceId liberado (o null si la sesión no había reclamado nada).
     */
    public Long release(String sessionId) {
        Long deviceId = deviceBySession.remove(sessionId);
        if (deviceId != null) {
            sessionByDevice.remove(deviceId, sessionId);
        }
        return deviceId;
    }

    public boolean isOnline(Long deviceId) {
        return sessionByDevice.containsKey(deviceId);
    }

    public Set<Long> onlineDeviceIds() {
        return Collections.unmodifiableSet(sessionByDevice.keySet());
    }
}
