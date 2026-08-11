package baultServer.e2e;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.EventCapture;
import baultServer.testsupport.StompTestClient;
import baultServer.utils.WsEventOps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo E2E multi-device: dos devices del mismo user comparten una carpeta y
 * device2 pide su contenido (que dispara una RPC WS del server a device1).
 * También valida que device2 recibe el evento {@code folder.created} en su canal
 * de eventos personal cuando device1 crea la carpeta.
 */
class MultiDeviceFolderE2ETest extends AbstractE2ETest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("Dos devices del mismo user: comparten carpeta, device2 browsea vía RPC WS, y ambos reciben folder.created")
    void twoDevicesShareAndBrowseFolder() throws Exception {
        resetMailbox();

        // 1. Registro + verificación + login de dos devices distintos
        String email = registerAndVerify();
        LoginResult device1 = loginAsNewDevice(email, "laptop");
        LoginResult device2 = loginAsNewDevice(email, "phone");
        assertThat(device1.deviceId()).isNotEqualTo(device2.deviceId());

        try (StompTestClient stomp1 = StompTestClient.connect(port, device1.accessToken());
             StompTestClient stomp2 = StompTestClient.connect(port, device2.accessToken())) {

            // 2. Ambos se suscriben a su canal de eventos personal
            EventCapture events1 = new EventCapture(mapper);
            EventCapture events2 = new EventCapture(mapper);
            stomp1.session().subscribe("/user/queue/events", events1);
            stomp2.session().subscribe("/user/queue/events", events2);

            // 3. device1 monta un handler que responde fs.list con entradas ficticias
            CompletableFuture<JsonNode> receivedRpc = new CompletableFuture<>();
            StompSession session1 = stomp1.session();
            session1.subscribe("/queue/device." + device1.deviceId(), new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        JsonNode rpc = mapper.readTree((byte[]) payload);
                        receivedRpc.complete(rpc);

                        ObjectNode reply = mapper.createObjectNode();
                        reply.put("correlationId", rpc.get("correlationId").asString());
                        reply.put("status", "ok");
                        ArrayNode entries = reply.putArray("entries");
                        ObjectNode entry = entries.addObject();
                        entry.put("name", "foo.txt");
                        entry.put("size", 123);
                        entry.put("isDirectory", false);

                        session1.send("/app/fs.list.reply", mapper.writeValueAsBytes(reply));
                    } catch (Exception e) {
                        receivedRpc.completeExceptionally(e);
                    }
                }
            });

            // 4. device2 se suscribe a su queue para pasar a online
            stomp2.session().subscribe("/queue/device." + device2.deviceId(), StompTestClient.noopHandler());

            // 5. Espera a que la presencia se propague: ambos online
            await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                ResponseEntity<Map<String, Object>> listResp = getJson("/api/devices", device2.accessToken());
                assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> devices = (List<Map<String, Object>>) listResp.getBody().get("devices");
                assertThat(devices).hasSize(2);
                assertThat(devices).allSatisfy(d -> assertThat(d.get("online")).isEqualTo(Boolean.TRUE));
            });

            //Base limpia antes de la acción bajo prueba (crear la carpeta): ignoramos los
            //device.presence de la fase de setup para las próximas aserciones.
            events1.clear();
            events2.clear();

            // 6. device1 crea la carpeta compartida
            ResponseEntity<Map<String, Object>> createResp = postJson(
                    "/api/folders",
                    Map.of("path", "/shared-docs", "sharing", "READ"),
                    device1.accessToken());
            assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> folder = createResp.getBody();
            assertThat(folder).isNotNull();
            assertThat(folder.get("sharing")).isEqualTo("READ");
            assertThat(folder.get("enabled")).isEqualTo(Boolean.TRUE);
            long folderId = ((Number) folder.get("id")).longValue();
            assertThat(((Number) folder.get("deviceId")).longValue()).isEqualTo(device1.deviceId());

            // 7. Ambos devices reciben el evento folder.created por su canal /user/queue/events.
            //    El creador (device1) también lo recibe (fan-out simple, el cliente ignora los suyos si quiere).
            JsonNode createdOnDevice1 = events1.awaitOne(
                    e -> WsEventOps.FOLDER_CREATED.equals(e.get("op").asString())
                            && e.get("data").get("folder").get("id").asLong() == folderId,
                    3);
            JsonNode createdOnDevice2 = events2.awaitOne(
                    e -> WsEventOps.FOLDER_CREATED.equals(e.get("op").asString())
                            && e.get("data").get("folder").get("id").asLong() == folderId,
                    3);
            assertThat(createdOnDevice1.get("data").get("folder").get("path").asString()).isEqualTo("/shared-docs");
            assertThat(createdOnDevice1.get("data").get("folder").get("sharing").asString()).isEqualTo("READ");
            assertThat(createdOnDevice2.get("data").get("folder").get("path").asString()).isEqualTo("/shared-docs");
            assertThat(((Number) createdOnDevice2.get("data").get("folder").get("deviceId").asLong()))
                    .isEqualTo(device1.deviceId());

            // 8. device2 lista folders compartidos con él: debe ver la nueva
            ResponseEntity<Map<String, Object>> sharedResp = getJson(
                    "/api/folders/shared-with-me", device2.accessToken());
            assertThat(sharedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sharedFolders =
                    (List<Map<String, Object>>) sharedResp.getBody().get("folders");
            assertThat(sharedFolders)
                    .as("device2 debe ver la carpeta compartida por device1")
                    .anySatisfy(f -> {
                        assertThat(((Number) f.get("id")).longValue()).isEqualTo(folderId);
                        assertThat(f.get("path")).isEqualTo("/shared-docs");
                        assertThat(((Number) f.get("deviceId")).longValue()).isEqualTo(device1.deviceId());
                    });

            // 9. device2 pide el contenido: HTTP → server publica RPC en /queue/device.{d1}
            //    → handler de device1 responde con /app/fs.list.reply → server resuelve el future
            //    y responde el HTTP con las entradas ficticias.
            ResponseEntity<Map<String, Object>> browseResp = getJson(
                    "/api/folders/browse/" + folderId, device2.accessToken());
            assertThat(browseResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            Map<String, Object> browseBody = browseResp.getBody();
            assertThat(browseBody).isNotNull();
            assertThat(((Number) browseBody.get("folderId")).longValue()).isEqualTo(folderId);
            assertThat(browseBody.get("path")).isEqualTo("/");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) browseBody.get("entries");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0))
                    .containsEntry("name", "foo.txt")
                    .containsEntry("isDirectory", Boolean.FALSE);
            assertThat(((Number) entries.get(0).get("size")).longValue()).isEqualTo(123L);

            // 10. Verifica también que device1 recibió la RPC correcta
            JsonNode rpc = receivedRpc.get(2, TimeUnit.SECONDS);
            assertThat(rpc.get("op").asString()).isEqualTo("fs.list");
            assertThat(rpc.get("folderId").asLong()).isEqualTo(folderId);
            assertThat(rpc.get("correlationId").asString()).isNotBlank();
        }
    }
}
