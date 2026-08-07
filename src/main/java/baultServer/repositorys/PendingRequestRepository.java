package baultServer.repositorys;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Almacén in-memory de peticiones RPC pendientes indexadas por correlationId.
 * No sobrevive a reinicios ni se sincroniza entre instancias del servidor.
 */
@Repository
public class PendingRequestRepository {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public boolean putIfAbsent(String correlationId, Long expectedDeviceId, CompletableFuture<JsonNode> future) {
        return pending.putIfAbsent(correlationId, new Pending(expectedDeviceId, future)) == null;
    }

    public Pending get(String correlationId) {
        return pending.get(correlationId);
    }

    public Pending remove(String correlationId) {
        return pending.remove(correlationId);
    }

    public record Pending(Long expectedDeviceId, CompletableFuture<JsonNode> future) {}
}
