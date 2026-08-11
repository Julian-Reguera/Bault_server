package baultServer.e2e;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.EventCapture;
import baultServer.testsupport.StompTestClient;
import baultServer.utils.WsEventOps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifica el fan-out de eventos {@code {op, data}} por el canal personal del usuario
 * ({@code /user/queue/events}). Con dos devices del mismo user conectados:
 * <ul>
 *   <li>Cuando device2 se conecta, device1 recibe {@code device.presence online=true}.</li>
 *   <li>Cuando device1 crea una carpeta compartida, device2 recibe {@code folder.created}.</li>
 *   <li>Cuando device1 cambia el sharing, device2 recibe {@code folder.updated}.</li>
 *   <li>Cuando device1 borra la carpeta (unshare), device2 recibe {@code folder.deleted}.</li>
 *   <li>Cuando device2 se desconecta, device1 recibe {@code device.presence online=false}.</li>
 * </ul>
 */
class EventNotificationsE2ETest extends AbstractE2ETest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("Fan-out por /user/queue/events: presencia + folder.created/updated/deleted a todos los devices del user")
    void broadcastsEventsToAllUserDevices() throws Exception {
        resetMailbox();

        String email = registerAndVerify();
        LoginResult device1 = loginAsNewDevice(email, "laptop");
        LoginResult device2 = loginAsNewDevice(email, "phone");

        StompTestClient s1 = null;
        StompTestClient s2 = null;
        try {
            s1 = StompTestClient.connect(port, device1.accessToken());
            EventCapture events1 = new EventCapture(mapper);
            s1.session().subscribe("/user/queue/events", events1);
            //Suscripción al canal RPC personal: gate para pasar a "online".
            s1.session().subscribe("/queue/device." + device1.deviceId(), StompTestClient.noopHandler());

            //Esperamos a que device1 aparezca online antes de conectar el segundo
            //(así device1 sí verá el device.presence de device2 conectándose).
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                ResponseEntity<Map<String, Object>> resp = getJson("/api/devices", device1.accessToken());
                @SuppressWarnings("unchecked")
                var devs = (java.util.List<Map<String, Object>>) resp.getBody().get("devices");
                return devs.stream()
                        .filter(d -> ((Number) d.get("id")).longValue() == device1.deviceId())
                        .findFirst()
                        .map(d -> Boolean.TRUE.equals(d.get("online")))
                        .orElse(false);
            });
            events1.clear();

            s2 = StompTestClient.connect(port, device2.accessToken());
            EventCapture events2 = new EventCapture(mapper);
            s2.session().subscribe("/user/queue/events", events2);
            s2.session().subscribe("/queue/device." + device2.deviceId(), StompTestClient.noopHandler());

            // 1. device1 debe ver device.presence de device2 llegando online
            JsonNode presenceOnline = events1.awaitOne(
                    e -> WsEventOps.DEVICE_PRESENCE.equals(e.get("op").asString())
                            && e.get("data").get("deviceId").asLong() == device2.deviceId()
                            && e.get("data").get("online").asBoolean(),
                    3);
            assertThat(presenceOnline.get("data").get("online").asBoolean()).isTrue();
            events1.clear();

            // 2. device1 crea carpeta → ambos reciben folder.created
            ResponseEntity<Map<String, Object>> createResp = postJson(
                    "/api/folders",
                    Map.of("path", "/shared-live", "sharing", "READ"),
                    device1.accessToken());
            assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long folderId = ((Number) createResp.getBody().get("id")).longValue();

            JsonNode createdEv = events1.awaitOne(
                    e -> WsEventOps.FOLDER_CREATED.equals(e.get("op").asString())
                            && e.get("data").get("folder").get("id").asLong() == folderId,
                    3);
            assertThat(createdEv.get("data").get("folder").get("path").asString()).isEqualTo("/shared-live");
            assertThat(createdEv.get("data").get("folder").get("sharing").asString()).isEqualTo("READ");
            events1.clear();

            // 3. device1 sube el sharing a READ_WRITE → folder.updated
            var patchDone = client().patch()
                    .uri("/api/folders/" + folderId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + device1.accessToken())
                    .body(Map.of("sharing", "READ_WRITE"))
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(patchDone.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(patchDone.getBody().get("sharing")).isEqualTo("READ_WRITE");

            JsonNode updatedEv = events1.awaitOne(
                    e -> WsEventOps.FOLDER_UPDATED.equals(e.get("op").asString())
                            && e.get("data").get("folder").get("id").asLong() == folderId,
                    3);
            assertThat(updatedEv.get("data").get("folder").get("sharing").asString()).isEqualTo("READ_WRITE");
            events1.clear();

            // 4. device1 borra la carpeta → folder.deleted
            var deleteDone = client().delete()
                    .uri("/api/folders/" + folderId)
                    .header("Authorization", "Bearer " + device1.accessToken())
                    .retrieve()
                    .toBodilessEntity();
            assertThat(deleteDone.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            JsonNode deletedEv = events1.awaitOne(
                    e -> WsEventOps.FOLDER_DELETED.equals(e.get("op").asString())
                            && e.get("data").get("folderId").asLong() == folderId,
                    3);
            assertThat(deletedEv.get("data").get("folderId").asLong()).isEqualTo(folderId);
            events1.clear();

            // 5. device2 se desconecta → device1 recibe device.presence offline
            s2.close();
            s2 = null;
            events1.awaitOne(
                    e -> WsEventOps.DEVICE_PRESENCE.equals(e.get("op").asString())
                            && e.get("data").get("deviceId").asLong() == device2.deviceId()
                            && !e.get("data").get("online").asBoolean(),
                    3);
        } finally {
            if (s1 != null) s1.close();
            if (s2 != null) s2.close();
        }
    }
}
