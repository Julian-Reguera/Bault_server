package baultServer.e2e;

import java.lang.reflect.Type;
import java.util.List;
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

import baultServer.model.BillingPlan;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;
import baultServer.repositorys.UserRepository;
import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.EventCapture;
import baultServer.testsupport.StompTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo E2E de una transferencia third-party: un tercer device (owner) orquesta el envío
 * de un archivo entre otros dos devices (sender y receiver) del mismo user.
 * <ol>
 *   <li>User + tres devices: owner, sender, receiver.</li>
 *   <li>sender crea la carpeta origen (sharing READ); receiver crea la carpeta destino (READ_WRITE).</li>
 *   <li>owner llama a {@code POST /api/transfers/third-party-request}.</li>
 *   <li>sender recibe {@code transfer.upload-requested} y receiver {@code transfer.download-offered},
 *       ambos con {@code thirdParty:true}.</li>
 *   <li>Ambos peers ven la transferencia consultando {@code GET /api/transfers/pending-as-peer}
 *       con el {@code role} correspondiente.</li>
 *   <li>receiver lanza {@code /download} y sender {@code /upload}: los bytes fluyen a través
 *       del pipe del servidor y coinciden con el original.</li>
 *   <li>Se emite {@code transfer.updated COMPLETED}.</li>
 * </ol>
 */
class ThirdPartyTransferE2ETest extends AbstractE2ETest {

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingPlanRepository billingPlanRepository;

    @Test
    @DisplayName("Third-party: un tercer device orquesta la transferencia; ambos peers la ven y la completan")
    void thirdPartyTransferHappyPath() throws Exception {
        resetMailbox();

        // 1. User + tres devices (FREE::maxDevices=2 no llega, subimos a PRO antes de registrar)
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult owner = loginAsNewDevice(email, "owner-desktop");
        LoginResult sender = loginAsNewDevice(email, "sender-phone");
        LoginResult receiver = loginAsNewDevice(email, "receiver-laptop");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (StompTestClient stompO = StompTestClient.connect(port, owner.accessToken());
             StompTestClient stompS = StompTestClient.connect(port, sender.accessToken());
             StompTestClient stompR = StompTestClient.connect(port, receiver.accessToken())) {

            // 2. Suscripciones: eventos del user (via owner) + queues RPC de sender/receiver
            EventCapture events = new EventCapture(mapper);
            stompO.session().subscribe("/user/queue/events", events);

            CopyOnWriteArrayList<JsonNode> senderNotifications = new CopyOnWriteArrayList<>();
            stompS.session().subscribe("/queue/device." + sender.deviceId(), new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    try { senderNotifications.add(mapper.readTree((byte[]) payload)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            CopyOnWriteArrayList<JsonNode> receiverNotifications = new CopyOnWriteArrayList<>();
            stompR.session().subscribe("/queue/device." + receiver.deviceId(), new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    try { receiverNotifications.add(mapper.readTree((byte[]) payload)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            //Owner también suscrito a su queue para reclamar presencia (aunque no espere nada).
            stompO.session().subscribe("/queue/device." + owner.deviceId(),
                    StompTestClient.noopHandler());

            // 3. Los tres devices online (visto desde el owner)
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                ResponseEntity<Map<String, Object>> resp = getJson("/api/devices", owner.accessToken());
                @SuppressWarnings("unchecked")
                var devs = (java.util.List<Map<String, Object>>) resp.getBody().get("devices");
                assertThat(devs).hasSize(3);
                assertThat(devs).allSatisfy(d -> assertThat(d.get("online")).isEqualTo(Boolean.TRUE));
            });
            events.clear();
            senderNotifications.clear();
            receiverNotifications.clear();

            // 4. Carpetas: sender comparte origen (READ), receiver comparte destino (READ_WRITE)
            ResponseEntity<Map<String, Object>> originResp = postJson(
                    "/api/folders",
                    Map.of("path", "/pictures", "sharing", "READ"),
                    sender.accessToken());
            assertThat(originResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long originFolderId = ((Number) originResp.getBody().get("id")).longValue();

            ResponseEntity<Map<String, Object>> destResp = postJson(
                    "/api/folders",
                    Map.of("path", "/inbox", "sharing", "READ_WRITE"),
                    receiver.accessToken());
            assertThat(destResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long destinationFolderId = ((Number) destResp.getBody().get("id")).longValue();

            // 5. Archivo determinista de 96 KB
            byte[] fileBytes = new byte[96 * 1024];
            new Random(4242L).nextBytes(fileBytes);

            // 6. owner crea la transferencia third-party
            ResponseEntity<Map<String, Object>> reqResp = postJson(
                    "/api/transfers/third-party-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "receiverDeviceId", receiver.deviceId(),
                            "originFolderId", originFolderId,
                            "destinationFolderId", destinationFolderId,
                            "originPath", "IMG_042.jpg",
                            "destinationPath", "IMG_042.jpg",
                            "sizeBytes", fileBytes.length),
                    owner.accessToken());
            assertThat(reqResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long transferId = ((Number) reqResp.getBody().get("transferId")).longValue();

            // 7. sender recibe transfer.upload-requested; receiver recibe transfer.download-offered.
            //    Ambos con thirdParty:true y requesterDeviceId apuntando al owner.
            await().atMost(3, TimeUnit.SECONDS).until(() ->
                    senderNotifications.stream().anyMatch(n ->
                            "transfer.upload-requested".equals(n.get("op").asString())
                                    && n.get("data").get("transferId").asLong() == transferId));
            JsonNode uploadReq = senderNotifications.stream()
                    .filter(n -> "transfer.upload-requested".equals(n.get("op").asString()))
                    .findFirst().orElseThrow();
            assertThat(uploadReq.get("data").get("requesterDeviceId").asLong()).isEqualTo(owner.deviceId());
            assertThat(uploadReq.get("data").get("thirdParty").asBoolean()).isTrue();
            assertThat(uploadReq.get("data").get("originFolderId").asLong()).isEqualTo(originFolderId);

            await().atMost(3, TimeUnit.SECONDS).until(() ->
                    receiverNotifications.stream().anyMatch(n ->
                            "transfer.download-offered".equals(n.get("op").asString())
                                    && n.get("data").get("transferId").asLong() == transferId));
            JsonNode downloadOffer = receiverNotifications.stream()
                    .filter(n -> "transfer.download-offered".equals(n.get("op").asString()))
                    .findFirst().orElseThrow();
            assertThat(downloadOffer.get("data").get("requesterDeviceId").asLong()).isEqualTo(owner.deviceId());
            assertThat(downloadOffer.get("data").get("thirdParty").asBoolean()).isTrue();
            assertThat(downloadOffer.get("data").get("destinationFolderId").asLong()).isEqualTo(destinationFolderId);

            // 8. Ambos peers ven la transferencia via /pending-as-peer con el role correcto.
            //    El owner NO debe verla (no participa como peer).
            ResponseEntity<Map<String, Object>> senderPending = getJson(
                    "/api/transfers/pending-as-peer", sender.accessToken());
            assertThat(senderPending.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> senderItems = (List<Map<String, Object>>) senderPending.getBody().get("transfers");
            assertThat(senderItems).hasSize(1);
            Map<String, Object> senderItem = senderItems.get(0);
            assertThat(((Number) senderItem.get("transferId")).longValue()).isEqualTo(transferId);
            assertThat(senderItem.get("role")).isEqualTo("sender");
            assertThat(((Number) senderItem.get("ownerDeviceId")).longValue()).isEqualTo(owner.deviceId());

            ResponseEntity<Map<String, Object>> receiverPending = getJson(
                    "/api/transfers/pending-as-peer", receiver.accessToken());
            assertThat(receiverPending.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> receiverItems = (List<Map<String, Object>>) receiverPending.getBody().get("transfers");
            assertThat(receiverItems).hasSize(1);
            Map<String, Object> receiverItem = receiverItems.get(0);
            assertThat(((Number) receiverItem.get("transferId")).longValue()).isEqualTo(transferId);
            assertThat(receiverItem.get("role")).isEqualTo("receiver");

            ResponseEntity<Map<String, Object>> ownerPending = getJson(
                    "/api/transfers/pending-as-peer", owner.accessToken());
            assertThat(ownerPending.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ownerItems = (List<Map<String, Object>>) ownerPending.getBody().get("transfers");
            assertThat(ownerItems)
                    .as("el owner no participa como peer y no debe ver la transferencia en /pending-as-peer")
                    .isEmpty();

            // 9. Fan-out transfer.created (llega también al owner via /user/queue/events)
            events.awaitOne(e ->
                    "transfer.created".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "PENDING".equals(e.get("data").get("status").asString()),
                    3);

            // 10. Lanzamos download (receiver) → bloquea en awaitMetadata hasta que upload publique.
            CompletableFuture<byte[]> downloadFuture = CompletableFuture.supplyAsync(() ->
                    client().get()
                            .uri("/api/transfers/" + transferId + "/download")
                            .header("Authorization", "Bearer " + receiver.accessToken())
                            .retrieve()
                            .body(byte[].class),
                    pool);

            //Esperamos al IN_PROGRESS antes de disparar el upload (mismo patrón que TransferE2ETest).
            events.awaitOne(e ->
                    "transfer.updated".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "IN_PROGRESS".equals(e.get("data").get("status").asString()),
                    5);

            // 11. Lanzamos upload (sender) en paralelo
            CompletableFuture<ResponseEntity<Void>> uploadFuture = CompletableFuture.supplyAsync(() ->
                    client().post()
                            .uri("/api/transfers/" + transferId + "/upload")
                            .header("Authorization", "Bearer " + sender.accessToken())
                            .header("X-Filename", "IMG_042.jpg")
                            .header("X-Content-Type", "image/jpeg")
                            .header("Content-Length", String.valueOf(fileBytes.length))
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(fileBytes)
                            .retrieve()
                            .toBodilessEntity(),
                    pool);

            // 12. Ambos completan; bytes idénticos y status HTTP correctos
            byte[] received = downloadFuture.get(15, TimeUnit.SECONDS);
            ResponseEntity<Void> uploadResp = uploadFuture.get(15, TimeUnit.SECONDS);

            assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(received)
                    .as("los bytes que recibe el receiver deben coincidir con los del sender")
                    .isEqualTo(fileBytes);

            // 13. transfer.updated COMPLETED
            events.awaitOne(e ->
                    "transfer.updated".equals(e.get("op").asString())
                            && e.get("data").get("transferId").asLong() == transferId
                            && "COMPLETED".equals(e.get("data").get("status").asString()),
                    5);

            // 14. Tras completarse ya no debe aparecer en /pending-as-peer
            ResponseEntity<Map<String, Object>> senderPendingAfter = getJson(
                    "/api/transfers/pending-as-peer", sender.accessToken());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> senderItemsAfter = (List<Map<String, Object>>)
                    senderPendingAfter.getBody().get("transfers");
            assertThat(senderItemsAfter)
                    .as("una vez COMPLETED la transferencia ya no debe listarse como pendiente")
                    .isEmpty();
        } finally {
            pool.shutdown();
        }
    }

    /** Sube el usuario a PRO (maxDevices=10) para poder registrar 3 devices ACTIVE. */
    private void upgradeToPro(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        BillingPlan pro = billingPlanRepository.findByName("PRO").orElseThrow();
        u.setBillingPlan(pro);
        userRepository.save(u);
    }
}
