package baultServer.services;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import baultServer.repositorys.PendingRequestRepository;
import baultServer.repositorys.PendingRequestRepository.Pending;

@Service
public class PendingRequestService {

    private final PendingRequestRepository repository;

    public PendingRequestService(PendingRequestRepository repository) {
        this.repository = repository;
    }

    public record Registration(String correlationId, CompletableFuture<JsonNode> future) {}

    /** Genera un correlationId único y registra un Future a la espera de la respuesta del device. */
    public Registration register(Long expectedDeviceId) {
        while (true) {
            String correlationId = UUID.randomUUID().toString();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            if (repository.putIfAbsent(correlationId, expectedDeviceId, future)) {
                return new Registration(correlationId, future);
            }
            //Colisión de UUID (astronómicamente improbable): reintentar con otro.
        }
    }

    /**
     * Completa la petición pendiente solo si el dispositivo que responde coincide
     * con el que se esperaba. Si no coincide, se ignora silenciosamente para no
     * confirmar al atacante que el correlationId existe.
     */
    public void complete(String correlationId, Long callerDeviceId, JsonNode payload) {
        Pending p = repository.get(correlationId);
        if (p == null || !p.expectedDeviceId().equals(callerDeviceId)) {
            return;
        }
        repository.remove(correlationId);
        p.future().complete(payload);
    }

    public void fail(String correlationId, Long callerDeviceId, Throwable error) {
        Pending p = repository.get(correlationId);
        if (p == null || !p.expectedDeviceId().equals(callerDeviceId)) {
            return;
        }
        repository.remove(correlationId);
        p.future().completeExceptionally(error);
    }

    public void cancel(String correlationId) {
        repository.remove(correlationId);
    }
}
