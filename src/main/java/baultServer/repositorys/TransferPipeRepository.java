package baultServer.repositorys;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import baultServer.utils.streams.StreamingPipe;

/**
 * Catalogo in-memory de pipes de transferencia activos, indexado por transferId.
 * No sobrevive a reinicios ni se sincroniza entre instancias del servidor.
 */
@Repository
public class TransferPipeRepository {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Map<Long, StreamingPipe> pipes = new ConcurrentHashMap<>();

    public StreamingPipe getOrCreate(Long transferId) {
        return pipes.computeIfAbsent(transferId,
                id -> new StreamingPipe(BUFFER_SIZE, () -> pipes.remove(id)));
    }

    public StreamingPipe get(Long transferId) {
        return pipes.get(transferId);
    }

    public void remove(Long transferId) {
        pipes.remove(transferId);
    }
}
