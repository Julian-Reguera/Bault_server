package baultServer.e2e;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.EventCapture;
import baultServer.testsupport.StompTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo E2E happy-path de transferencia de archivos entre dos devices del mismo user:
 * <ol>
 *   <li>sender crea una carpeta compartida con {@code READ}.</li>
 *   <li>receiver hace {@code POST /api/transfers/download-request}.</li>
 *   <li>sender recibe {@code transfer.upload-requested} por su queue RPC.</li>
 *   <li>Todos los devices del user reciben {@code transfer.created} por
 *       {@code /user/queue/events}.</li>
 *   <li>En paralelo: receiver lanza {@code GET /download} y sender {@code POST /upload}.
 *       El server actúa como pipe: los bytes fluyen sender → server → receiver sin
 *       tocar disco.</li>
 *   <li>El receiver acumula los bytes y valida que coinciden con lo que envió el sender.</li>
 *   <li>Se emite {@code transfer.updated} con {@code status=COMPLETED}.</li>
 * </ol>
 * <p>
 * Para coordinar sin race condition: se espera al evento {@code transfer.updated IN_PROGRESS}
 * antes de disparar el upload, garantizando que el server ya está en la fase
 * {@code awaitMetadata} del pipe.
 */
class TransferE2ETest extends AbstractE2ETest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("Download-request happy path: sender→receiver via pipe streaming; bytes idénticos + eventos correctos")
    void downloadRequestHappyPath() throws Exception {
        resetMailbox();

        // 1. User + dos devices
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver-laptop");
        LoginResult sender = loginAsNewDevice(email, "sender-phone");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (StompTestClient stompR = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient stompS = StompTestClient.connect(port, sender.accessToken())) {

            // 2. Suscripciones: presencia + notificaciones de eventos + queue RPC del sender
            EventCapture events = new EventCapture(mapper);
            stompR.session().subscribe("/user/queue/events", events);

            CopyOnWriteArrayList<JsonNode> senderNotifications = new CopyOnWriteArrayList<>();
            stompS.session().subscribe("/queue/device." + sender.deviceId(), new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    try { senderNotifications.add(mapper.readTree((byte[]) payload)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            stompR.session().subscribe("/queue/device." + receiver.deviceId(),
                    StompTestClient.noopHandler());

            // 3. Ambos devices online
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                ResponseEntity<Map<String, Object>> resp = getJson("/api/devices", receiver.accessToken());
                @SuppressWarnings("unchecked")
                var devs = (java.util.List<Map<String, Object>>) resp.getBody().get("devices");
                assertThat(devs).hasSize(2);
                assertThat(devs).allSatisfy(d -> assertThat(d.get("online")).isEqualTo(Boolean.TRUE));
            });
            events.clear();
            senderNotifications.clear();

            // 4. sender crea la carpeta origen (compartida con READ)
            ResponseEntity<Map<String, Object>> folderResp = postJson(
                    "/api/folders",
                    Map.of("path", "/pictures", "sharing", "READ"),
                    sender.accessToken());
            assertThat(folderResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long folderId = ((Number) folderResp.getBody().get("id")).longValue();

            // 5. Preparamos el "archivo": 128 KB de bytes deterministas para poder comparar
            byte[] fileBytes = new byte[128 * 1024];
            new Random(1234L).nextBytes(fileBytes);

            // 6. receiver hace download-request
            ResponseEntity<Map<String, Object>> reqResp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "originFolderId", folderId,
                            "originPath", "IMG_001.jpg",
                            "sizeBytes", fileBytes.length),
                    receiver.accessToken());
            assertThat(reqResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long transferId = ((Number) reqResp.getBody().get("transferId")).longValue();

            // 7. sender recibe transfer.upload-requested por su queue RPC
            await().atMost(3, TimeUnit.SECONDS).until(() ->
                    senderNotifications.stream().anyMatch(n ->
                            "transfer.upload-requested".equals(n.get("op").asString())
                                    && n.get("data").get("transferId").asLong() == transferId));
            JsonNode uploadReq = senderNotifications.stream()
                    .filter(n -> "transfer.upload-requested".equals(n.get("op").asString()))
                    .findFirst().orElseThrow();
            assertThat(uploadReq.get("data").get("requesterDeviceId").asLong()).isEqualTo(receiver.deviceId());
            assertThat(uploadReq.get("data").get("originPath").asString()).isEqualTo("IMG_001.jpg");
            assertThat(uploadReq.get("data").get("sizeBytes").asLong()).isEqualTo(fileBytes.length);

            // 8. Todos los devices del user reciben transfer.created (fan-out)
            events.awaitOne(e ->
                    "transfer.created".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "PENDING".equals(e.get("data").get("status").asString()),
                    3);

            // 9. Lanzamos download en un hilo. Se bloquea en awaitMetadata hasta que el upload
            //    publique metadata, así que necesitamos que la transferencia esté IN_PROGRESS
            //    antes de disparar el upload.
            CompletableFuture<byte[]> downloadFuture = CompletableFuture.supplyAsync(() ->
                    client().get()
                            .uri("/api/transfers/" + transferId + "/download")
                            .header("Authorization", "Bearer " + receiver.accessToken())
                            .retrieve()
                            .body(byte[].class),
                    pool);

            //Esperamos al evento transfer.updated IN_PROGRESS antes del upload: garantiza
            //que el server ya está en fase awaitMetadata y no rechazará el upload con CONFLICT.
            events.awaitOne(e ->
                    "transfer.updated".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "IN_PROGRESS".equals(e.get("data").get("status").asString()),
                    5);

            // 10. Lanzamos upload en paralelo
            CompletableFuture<ResponseEntity<Void>> uploadFuture = CompletableFuture.supplyAsync(() ->
                    client().post()
                            .uri("/api/transfers/" + transferId + "/upload")
                            .header("Authorization", "Bearer " + sender.accessToken())
                            .header("X-Filename", "IMG_001.jpg")
                            .header("X-Content-Type", "image/jpeg")
                            .header("Content-Length", String.valueOf(fileBytes.length))
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(fileBytes)
                            .retrieve()
                            .toBodilessEntity(),
                    pool);

            // 11. Ambos completan; validamos bytes idénticos y status HTTP
            byte[] received = downloadFuture.get(15, TimeUnit.SECONDS);
            ResponseEntity<Void> uploadResp = uploadFuture.get(15, TimeUnit.SECONDS);

            assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(received)
                    .as("los bytes que recibe el receiver deben coincidir exactamente con los que envió el sender")
                    .isEqualTo(fileBytes);

            // 12. Evento transfer.updated COMPLETED
            JsonNode completed = events.awaitOne(e ->
                    "transfer.updated".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "COMPLETED".equals(e.get("data").get("status").asString()),
                    5);
            assertThat(completed.get("data").get("status").asString()).isEqualTo("COMPLETED");
        } finally {
            //shutdown ordenado: los dos futures ya se han resuelto en este punto,
            //así que no hay tareas vivas — usamos shutdown() en vez de shutdownNow()
            //para evitar interrumpir hilos y ensuciar el log con "response already committed".
            pool.shutdown();
        }
    }
}
