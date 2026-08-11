package baultServer.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.Transfer;
import baultServer.utils.WsEventOps;

/**
 * Emite eventos {@code {op, data}} al canal WebSocket personal del usuario
 * ({@code /user/{userId}/queue/events}). Spring hace fan-out a todas las
 * sesiones activas del mismo usuario: cualquier device conectado los recibe.
 * <p>
 * El device que provoca el evento también lo recibe (política intencional
 * para simplificar el fan-out); el cliente ignora los suyos comparando el
 * {@code deviceId} del payload con el suyo si le molestan.
 */
@Service
public class EventWsBroadcaster {

    private static final Logger log = LogManager.getLogger(EventWsBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public EventWsBroadcaster(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    //  Device
    // -----------------------------------------------------------------------

    public void devicePresence(Long userId, Long deviceId, boolean online) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("deviceId", deviceId);
        data.put("online", online);
        publish(userId, WsEventOps.DEVICE_PRESENCE, data);
    }

    public void deviceCreated(Long userId, Device device) {
        publish(userId, WsEventOps.DEVICE_CREATED, wrapDevice(device));
    }

    public void deviceUpdated(Long userId, Device device) {
        publish(userId, WsEventOps.DEVICE_UPDATED, wrapDevice(device));
    }

    public void deviceRemoved(Long userId, Long deviceId) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("deviceId", deviceId);
        publish(userId, WsEventOps.DEVICE_REMOVED, data);
    }

    // -----------------------------------------------------------------------
    //  Folder
    // -----------------------------------------------------------------------

    public void folderCreated(Long userId, Folder folder) {
        publish(userId, WsEventOps.FOLDER_CREATED, wrapFolder(folder));
    }

    public void folderUpdated(Long userId, Folder folder) {
        publish(userId, WsEventOps.FOLDER_UPDATED, wrapFolder(folder));
    }

    public void folderDeleted(Long userId, Long folderId) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("folderId", folderId);
        publish(userId, WsEventOps.FOLDER_DELETED, data);
    }

    // -----------------------------------------------------------------------
    //  Transfer
    // -----------------------------------------------------------------------

    public void transferCreated(Long userId, Long transferId, Transfer.Status status) {
        publish(userId, WsEventOps.TRANSFER_CREATED, transferPayload(transferId, status));
    }

    public void transferUpdated(Long userId, Long transferId, Transfer.Status status) {
        publish(userId, WsEventOps.TRANSFER_UPDATED, transferPayload(transferId, status));
    }

    private ObjectNode transferPayload(Long transferId, Transfer.Status status) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("transferId", transferId);
        data.put("status", status.toString());
        return data;
    }

    // -----------------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------------

    private ObjectNode wrapDevice(Device device) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.set("device", objectMapper.valueToTree(device.toTransfer()));
        return data;
    }

    private ObjectNode wrapFolder(Folder folder) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.set("folder", objectMapper.valueToTree(folder.toTransfer()));
        return data;
    }

    private void publish(Long userId, String op, JsonNode data) {
        if (userId == null) {
            log.warn("publish skipped: null userId (op={})", op);
            return;
        }
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("op", op);
        event.set("data", data);
        //Spring resuelve /user/{userId}/queue/events y hace fan-out a todas
        //las sesiones asociadas a ese Principal.getName().
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), WsEventOps.USER_EVENTS_QUEUE, event);
    }
}
