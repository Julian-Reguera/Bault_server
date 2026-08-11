package baultServer.e2e;

import java.util.List;
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
 * Ciclo de vida de presencia visto por otro device del mismo usuario:
 * <ol>
 *   <li>device1 se conecta al WS y se suscribe al canal de eventos personal.</li>
 *   <li>device2 hace login (HTTP): device1 recibe {@code device.created}.</li>
 *   <li>device2 conecta/desconecta el WS varias veces; en cada ciclo device1 recibe
 *       {@code device.presence online=true} y luego {@code online=false}.</li>
 *   <li>Tras cada transición, {@code GET /api/devices} refleja el estado real de device2.</li>
 * </ol>
 */
class DevicePresenceLifecycleE2ETest extends AbstractE2ETest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("device.created al login del segundo device + device.presence en cada reconexión, con /api/devices coherente")
    void device2LifecycleIsPropagatedToDevice1() throws Exception {
        resetMailbox();

        // 1. Registro + login del primer device
        String email = registerAndVerify();
        LoginResult device1 = loginAsNewDevice(email, "laptop");

        try (StompTestClient stomp1 = StompTestClient.connect(port, device1.accessToken())) {
            EventCapture events1 = new EventCapture(mapper);
            stomp1.session().subscribe("/user/queue/events", events1);
            //Reclamamos presencia para device1 antes de crear device2, así el broadcaster
            //ya tiene una sesión activa a la que enviar los eventos posteriores.
            stomp1.session().subscribe("/queue/device." + device1.deviceId(), StompTestClient.noopHandler());

            //Espera a que device1 aparezca online por su suscripción al canal RPC.
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                @SuppressWarnings("unchecked")
                var devs = (List<Map<String, Object>>) getJson("/api/devices", device1.accessToken())
                        .getBody().get("devices");
                return devs.stream()
                        .filter(d -> ((Number) d.get("id")).longValue() == device1.deviceId())
                        .findFirst().map(d -> Boolean.TRUE.equals(d.get("online"))).orElse(false);
            });
            events1.clear();

            // 2. LOGIN de device2 (HTTP) → device1 recibe device.created con el Device serializado
            LoginResult device2 = loginAsNewDevice(email, "phone");

            JsonNode createdEv = events1.awaitOne(
                    e -> WsEventOps.DEVICE_CREATED.equals(e.get("op").asString())
                            && e.get("data").get("device").get("id").asLong() == device2.deviceId(),
                    3);
            assertThat(createdEv.get("data").get("device").get("alias").asString()).isEqualTo("phone");
            //Recién creado → todavía offline (no ha abierto el WS).
            assertThat(createdEv.get("data").get("device").get("online").asBoolean()).isFalse();

            //Sanidad: GET /api/devices (desde device1) devuelve 2 devices y device2 offline.
            List<Map<String, Object>> devices = fetchDevices(device1.accessToken());
            assertThat(devices).hasSize(2);
            Map<String, Object> d2InList = findDevice(devices, device2.deviceId());
            assertThat(d2InList.get("online")).isEqualTo(Boolean.FALSE);
            assertThat(d2InList.get("alias")).isEqualTo("phone");

            // 3. Ciclo de conexión/desconexión de device2: 3 iteraciones
            for (int i = 1; i <= 3; i++) {
                final int cycle = i; //capturado por lambdas de await/untilAsserted
                events1.clear();

                //Conexión: device2 abre WS + reclama presencia
                try (StompTestClient stomp2 = StompTestClient.connect(port, device2.accessToken())) {
                    stomp2.session().subscribe("/queue/device." + device2.deviceId(),
                            StompTestClient.noopHandler());

                    //device1 debe ver device.presence online=true
                    events1.awaitOne(
                            e -> WsEventOps.DEVICE_PRESENCE.equals(e.get("op").asString())
                                    && e.get("data").get("deviceId").asLong() == device2.deviceId()
                                    && e.get("data").get("online").asBoolean(),
                            3);

                    //GET /api/devices lo refleja
                    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                        Map<String, Object> d2 = findDevice(
                                fetchDevices(device1.accessToken()), device2.deviceId());
                        assertThat(d2.get("online"))
                                .as("ciclo %d: device2 debe estar online tras conectar", cycle)
                                .isEqualTo(Boolean.TRUE);
                    });
                    events1.clear();
                    //try-with-resources cerrará stomp2 aquí y dispara el disconnect
                }

                //Desconexión: device1 debe ver device.presence online=false
                events1.awaitOne(
                        e -> WsEventOps.DEVICE_PRESENCE.equals(e.get("op").asString())
                                && e.get("data").get("deviceId").asLong() == device2.deviceId()
                                && !e.get("data").get("online").asBoolean(),
                        3);

                //Y GET /api/devices vuelve a marcarlo offline
                await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                    Map<String, Object> d2 = findDevice(
                            fetchDevices(device1.accessToken()), device2.deviceId());
                    assertThat(d2.get("online"))
                            .as("ciclo %d: device2 debe estar offline tras desconectar", cycle)
                            .isEqualTo(Boolean.FALSE);
                });
            }

            //Sanidad final: device1 sigue estando online tras todo el trasiego.
            Map<String, Object> d1Final = findDevice(
                    fetchDevices(device1.accessToken()), device1.deviceId());
            assertThat(d1Final.get("online"))
                    .as("device1 no debe haberse visto afectado por los reciclajes de device2")
                    .isEqualTo(Boolean.TRUE);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private List<Map<String, Object>> fetchDevices(String accessToken) {
        ResponseEntity<Map<String, Object>> resp = getJson("/api/devices", accessToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) resp.getBody().get("devices");
        return devices;
    }

    private Map<String, Object> findDevice(List<Map<String, Object>> devices, Long id) {
        return devices.stream()
                .filter(d -> ((Number) d.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Device " + id + " no está en la lista"));
    }
}
