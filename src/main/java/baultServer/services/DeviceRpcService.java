package baultServer.services;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import baultServer.exceptions.ApiErrorCode;
import baultServer.exceptions.ApiException;
import baultServer.exceptions.DeviceDeniedException;
import baultServer.model.Device;
import baultServer.repositorys.DevicePresenceRepository;
import baultServer.services.PendingRequestService.Registration;

@Service
public class DeviceRpcService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private final SimpMessagingTemplate messagingTemplate;
    private final DevicePresenceRepository presenceRepository;
    private final PendingRequestService pendingRequests;

    public DeviceRpcService(SimpMessagingTemplate messagingTemplate,
                            DevicePresenceRepository presenceRepository,
                            PendingRequestService pendingRequests) {
        this.messagingTemplate = messagingTemplate;
        this.presenceRepository = presenceRepository;
        this.pendingRequests = pendingRequests;
    }

    public JsonNode listFolderEntries(Device targetDevice, Long folderId, String subPath) {
        if (!presenceRepository.isOnline(targetDevice.getId())) {
            throw new ApiException(ApiErrorCode.DEVICE_OFFLINE, java.util.Map.of("role", "owner"));
        }

        Registration registration = pendingRequests.register(targetDevice.getId());
        String correlationId = registration.correlationId();

        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("correlationId", correlationId);
        request.put("op", "fs.list");
        request.put("folderId", folderId);
        request.put("path", subPath == null ? "/" : subPath);

        messagingTemplate.convertAndSend("/queue/device." + targetDevice.getId(), request);

        try {
            return registration.future().get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.cancel(correlationId);
            throw new ApiException(ApiErrorCode.DEVICE_RPC_TIMEOUT);
        } catch (InterruptedException e) {
            pendingRequests.cancel(correlationId);
            Thread.currentThread().interrupt();
            throw new ApiException(ApiErrorCode.DEVICE_RPC_INTERRUPTED);
        } catch (java.util.concurrent.ExecutionException e) {
            pendingRequests.cancel(correlationId);
            Throwable cause = e.getCause();
            if (cause instanceof DeviceDeniedException denied) {
                throw new ApiException(ApiErrorCode.DEVICE_RPC_DENIED, denied.getMessage());
            }
            throw new ApiException(ApiErrorCode.DEVICE_RPC_ERROR,
                    cause == null ? ApiErrorCode.DEVICE_RPC_ERROR.defaultMessage() : cause.getMessage());
        }
    }
}
