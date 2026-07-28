package baultServer.repositorys;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * Registro en memoria de las sesiones WebSocket activas por dispositivo.
 * Un mismo dispositivo puede tener varias sesiones abiertas simultáneamente
 * (reconexiones solapadas, varias pestañas, etc.), por lo que se guarda el
 * conjunto de sessionIds y no un simple booleano.
 */
@Repository
public class DevicePresenceRepository {

    private final ConcurrentHashMap<Long, Set<String>> sessionsByDevice = new ConcurrentHashMap<>();

    /**
     * Registra una sesión WS para el dispositivo indicado.
     * Devuelve true si el dispositivo pasa de offline a online con esta llamada.
     */
    public boolean addSession(Long deviceId, String sessionId) {
        boolean[] becameOnline = { false };
        sessionsByDevice.compute(deviceId, (id, current) -> {
            if (current == null) {
                becameOnline[0] = true;
                Set<String> set = ConcurrentHashMap.newKeySet();
                set.add(sessionId);
                return set;
            }
            current.add(sessionId);
            return current;
        });
        return becameOnline[0];
    }

    /**
     * Elimina una sesión WS del dispositivo indicado.
     * Devuelve true si el dispositivo pasa de online a offline con esta llamada.
     */
    public boolean removeSession(Long deviceId, String sessionId) {
        boolean[] becameOffline = { false };
        sessionsByDevice.computeIfPresent(deviceId, (id, current) -> {
            current.remove(sessionId);
            if (current.isEmpty()) {
                becameOffline[0] = true;
                return null;
            }
            return current;
        });
        return becameOffline[0];
    }

    public boolean isOnline(Long deviceId) {
        Set<String> sessions = sessionsByDevice.get(deviceId);
        return sessions != null && !sessions.isEmpty();
    }

    public int sessionCount(Long deviceId) {
        Set<String> sessions = sessionsByDevice.get(deviceId);
        return sessions == null ? 0 : sessions.size();
    }

    public Set<Long> onlineDeviceIds() {
        return Collections.unmodifiableSet(sessionsByDevice.keySet());
    }
}