package baultServer.controllers.ws;

import java.util.Map;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import com.fasterxml.jackson.databind.JsonNode;

import baultServer.configs.ws.JwtHandshakeInterceptor;
import baultServer.exceptions.DeviceDeniedException;
import baultServer.services.PendingRequestService;

@Controller
public class FsReplyController {

    private final PendingRequestService pendingRequests;

    public FsReplyController(PendingRequestService pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @MessageMapping("/fs.list.reply")
    public void onListReply(@Payload JsonNode body,
                            @Header(name = SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, required = false)
                            Map<String, Object> sessionAttributes) {
        JsonNode idNode = body == null ? null : body.get("correlationId");
        if (idNode == null || !idNode.isTextual()) {
            return;
        }
        String correlationId = idNode.asText();

        Long callerDeviceId = deviceIdOf(sessionAttributes);
        if (callerDeviceId == null) {
            //Sin dispositivo en la sesión no podemos verificar quién responde: descartamos.
            return;
        }

        JsonNode statusNode = body.get("status");
        String status = (statusNode != null && statusNode.isTextual()) ? statusNode.asText() : "ok";

        switch (status) {
            case "denied" -> {
                JsonNode reason = body.get("reason");
                String msg = (reason != null && reason.isTextual()) ? reason.asText() : "Denied by device";
                pendingRequests.fail(correlationId, callerDeviceId, new DeviceDeniedException(msg));
            }
            case "error" -> {
                JsonNode error = body.get("error");
                String msg = (error != null && error.isTextual()) ? error.asText() : "Device error";
                pendingRequests.fail(correlationId, callerDeviceId, new RuntimeException(msg));
            }
            default -> pendingRequests.complete(correlationId, callerDeviceId, body.get("entries"));
        }
    }

    private static Long deviceIdOf(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) return null;
        Object value = sessionAttributes.get(JwtHandshakeInterceptor.DEVICE_ID_ATTR);
        return (value instanceof Long l) ? l : null;
    }
}
