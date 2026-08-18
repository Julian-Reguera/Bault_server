package baultServer.e2e;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import baultServer.model.Transfer;
import baultServer.repositorys.TransferRepository;

import baultServer.model.BillingPlan;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;
import baultServer.repositorys.UserRepository;
import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.StompTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Casos de error de {@code /api/transfers/**}: validación de request, autorización,
 * propiedad, presencia, estados y colisiones de estado protegidas por {@code @Version}.
 * Complementa {@code TransferE2ETest} (happy path).
 */
class TransferErrorCasesE2ETest extends AbstractE2ETest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingPlanRepository billingPlanRepository;

    @Autowired
    private TransferRepository transferRepository;

    // ---------- Validación de request ----------

    @Test
    @DisplayName("download-request con sender == receiver devuelve 400 TRANSFER_PEERS_MUST_DIFFER")
    void downloadRequestWithSamePeersReturns400() throws Exception {
        String email = registerAndVerify();
        LoginResult only = loginAsNewDevice(email, "only-device");
        try (StompTestClient stomp = StompTestClient.connect(port, only.accessToken())) {
            claimPresence(stomp, only.deviceId());
            waitOnline(only.accessToken(), 1);
            long folderId = createFolder(only.accessToken(), "/pictures", "READ");

            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", only.deviceId(),
                            "originFolderId", folderId,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    only.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_PEERS_MUST_DIFFER");
        }
    }

    @Test
    @DisplayName("download-request con sizeBytes negativo devuelve 400 TRANSFER_SIZE_NEGATIVE")
    void downloadRequestWithNegativeSizeReturns400() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            long folderId = createFolder(sender.accessToken(), "/pics", "READ");

            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "originFolderId", folderId,
                            "originPath", "IMG.jpg",
                            "sizeBytes", -1),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_SIZE_NEGATIVE");
        }
    }

    @Test
    @DisplayName("download-request omitiendo senderDeviceId devuelve 400 MISSING_FIELD con details.field")
    void downloadRequestWithMissingFieldReturns400() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        try (StompTestClient s = StompTestClient.connect(port, receiver.accessToken())) {
            claimPresence(s, receiver.deviceId());
            waitOnline(receiver.accessToken(), 1);
            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "originFolderId", 1L,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("code", "MISSING_FIELD");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
            assertThat(details).containsEntry("field", "senderDeviceId");
        }
    }

    @Test
    @DisplayName("third-party-request con owner == sender devuelve 400 TRANSFER_OWNER_MUST_DIFFER_FROM_PEERS")
    void thirdPartyRequestWithOwnerAsPeerReturns400() throws Exception {
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult owner = loginAsNewDevice(email, "owner");
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        try (StompTestClient a = StompTestClient.connect(port, owner.accessToken());
             StompTestClient b = StompTestClient.connect(port, receiver.accessToken())) {
            claimPresence(a, owner.deviceId());
            claimPresence(b, receiver.deviceId());
            waitOnline(owner.accessToken(), 2);
            long originFolderId = createFolder(owner.accessToken(), "/pics", "READ");
            long destFolderId = createFolder(receiver.accessToken(), "/inbox", "READ_WRITE");

            //Owner intenta actuar también como sender.
            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/third-party-request",
                    Map.of(
                            "senderDeviceId", owner.deviceId(),
                            "receiverDeviceId", receiver.deviceId(),
                            "originFolderId", originFolderId,
                            "destinationFolderId", destFolderId,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    owner.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_OWNER_MUST_DIFFER_FROM_PEERS");
        }
    }

    // ---------- Autorización / propiedad ----------

    @Test
    @DisplayName("download-request con folder no propiedad del sender devuelve 403 FOLDER_NOT_OWNED_BY_DEVICE")
    void downloadRequestWithFolderNotOwnedByPeerReturns403() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            //La folder pertenece al receiver, no al sender.
            long wrongOwnerFolder = createFolder(receiver.accessToken(), "/mine", "READ");

            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "originFolderId", wrongOwnerFolder,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody()).containsEntry("code", "FOLDER_NOT_OWNED_BY_DEVICE");
        }
    }

    @Test
    @DisplayName("download-request sobre folder con sharing NONE devuelve 403 FOLDER_SHARING_INSUFFICIENT")
    void downloadRequestWithInsufficientSharingReturns403() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            //Folder del sender pero SIN compartir (default = NONE).
            long unsharedFolder = createFolder(sender.accessToken(), "/private", "NONE");

            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "originFolderId", unsharedFolder,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody()).containsEntry("code", "FOLDER_SHARING_INSUFFICIENT");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
            assertThat(details).containsEntry("required", "READ").containsEntry("current", "NONE");
        }
    }

    @Test
    @DisplayName("deny por el owner (no por el peer) devuelve 403 TRANSFER_ONLY_PEER_MAY_DENY")
    void denyByOwnerReturns403() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            long folderId = createFolder(sender.accessToken(), "/pics", "READ");
            long transferId = createDownloadRequest(receiver, sender.deviceId(), folderId);

            //El owner de una download-request es el receiver; deny debe llamarlo el sender (peer).
            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/" + transferId + "/deny",
                    Map.of(),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_ONLY_PEER_MAY_DENY");
        }
    }

    // ---------- Presencia y estado ----------

    @Test
    @DisplayName("download-request con sender offline devuelve 503 DEVICE_OFFLINE con details.role=sender")
    void downloadRequestWithOfflineSenderReturns503() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        //sender crea folder por HTTP (no requiere WS) y NO se conecta a STOMP → offline.
        long folderId = createFolder(sender.accessToken(), "/pics", "READ");

        try (StompTestClient onlyReceiver = StompTestClient.connect(port, receiver.accessToken())) {
            claimPresence(onlyReceiver, receiver.deviceId());
            //Espera a que el receiver esté online pero el sender siga offline.
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                ResponseEntity<Map<String, Object>> devsResp = getJson("/api/devices", receiver.accessToken());
                @SuppressWarnings("unchecked")
                var devs = (java.util.List<Map<String, Object>>) devsResp.getBody().get("devices");
                assertThat(devs).hasSize(2);
                Map<String, Object> senderDev = devs.stream()
                        .filter(d -> ((Number) d.get("id")).longValue() == sender.deviceId())
                        .findFirst().orElseThrow();
                assertThat(senderDev.get("online")).isEqualTo(Boolean.FALSE);
            });

            ResponseEntity<Map<String, Object>> resp = postJson(
                    "/api/transfers/download-request",
                    Map.of(
                            "senderDeviceId", sender.deviceId(),
                            "originFolderId", folderId,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 1024),
                    receiver.accessToken());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody()).containsEntry("code", "DEVICE_OFFLINE");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
            assertThat(details).containsEntry("role", "sender");
        }
    }

    @Test
    @DisplayName("Handshake simétrico: /upload puede llegar antes que /download y la transferencia completa")
    void uploaderArrivesFirstHappyPath() throws Exception {
        String email = registerAndVerify();
        LoginResult sender = loginAsNewDevice(email, "sender");
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (StompTestClient a = StompTestClient.connect(port, sender.accessToken());
             StompTestClient b = StompTestClient.connect(port, receiver.accessToken())) {
            claimPresence(a, sender.deviceId());
            claimPresence(b, receiver.deviceId());
            waitOnline(sender.accessToken(), 2);
            long destFolderId = createFolder(receiver.accessToken(), "/inbox", "READ_WRITE");

            ResponseEntity<Map<String, Object>> reqResp = postJson(
                    "/api/transfers/upload-request",
                    Map.of(
                            "receiverDeviceId", receiver.deviceId(),
                            "destinationFolderId", destFolderId,
                            "originPath", "IMG.jpg",
                            "sizeBytes", 4),
                    sender.accessToken());
            assertThat(reqResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            long transferId = ((Number) reqResp.getBody().get("transferId")).longValue();

            byte[] payload = {1, 2, 3, 4};

            //Sender arranca /upload PRIMERO: bloquea en awaitRendezvous esperando al downloader.
            CompletableFuture<ResponseEntity<Void>> uploadFuture = CompletableFuture.supplyAsync(() ->
                    client().post()
                            .uri("/api/transfers/" + transferId + "/upload")
                            .header("Authorization", "Bearer " + sender.accessToken())
                            .header("X-Filename", "IMG.jpg")
                            .header("X-Content-Type", "image/jpeg")
                            .header("Content-Length", String.valueOf(payload.length))
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(payload)
                            .retrieve()
                            .toBodilessEntity(),
                    pool);

            //Pequeña espera para que el upload llegue al servidor y reclame plaza antes del download.
            Thread.sleep(200);
            assertThat(uploadFuture.isDone())
                    .as("upload debe estar bloqueado esperando handshake, no completo")
                    .isFalse();

            //Receiver arranca /download: completa el rendezvous y ambos progresan.
            CompletableFuture<byte[]> downloadFuture = CompletableFuture.supplyAsync(() ->
                    client().get()
                            .uri("/api/transfers/" + transferId + "/download")
                            .header("Authorization", "Bearer " + receiver.accessToken())
                            .retrieve()
                            .body(byte[].class),
                    pool);

            byte[] received = downloadFuture.get(15, TimeUnit.SECONDS);
            ResponseEntity<Void> uploadResp = uploadFuture.get(15, TimeUnit.SECONDS);

            assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(received).isEqualTo(payload);

            //El estado final debe ser COMPLETED.
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                Transfer t = transferRepository.findById(transferId).orElseThrow();
                assertThat(t.getStatus()).isEqualTo(Transfer.Status.COMPLETED);
                assertThat(t.getSizeBytes()).isEqualTo(payload.length);
            });
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("cancel durante el handshake aborta al peer bloqueado y deja la transferencia CANCELLED")
    void cancelDuringHandshakeAbortsWaitingPeer() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            long folderId = createFolder(sender.accessToken(), "/pics", "READ");
            long transferId = createDownloadRequest(receiver, sender.deviceId(), folderId);

            //Receiver (=owner en download-request) arranca /download. Bloquea en awaitRendezvous
            //porque el sender no ha llamado a /upload.
            CompletableFuture<ResponseEntity<byte[]>> downloadFuture = CompletableFuture.supplyAsync(() ->
                    client().get()
                            .uri("/api/transfers/" + transferId + "/download")
                            .header("Authorization", "Bearer " + receiver.accessToken())
                            .retrieve()
                            .toEntity(byte[].class),
                    pool);

            //Esperamos a que el download alcance el bloqueo del handshake.
            Thread.sleep(300);
            assertThat(downloadFuture.isDone()).isFalse();

            //Owner (=receiver) cancela mientras el download está bloqueado.
            ResponseEntity<Map<String, Object>> cancelResp = client().delete()
                    .uri("/api/transfers/" + transferId + "/cancel")
                    .header("Authorization", "Bearer " + receiver.accessToken())
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(cancelResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            //El download debe desbloquearse rápido. El status HTTP puede variar (409 con estado
            //final CANCELLED, o similar) pero lo que nos interesa es que NO se quede colgado los
            //30 s del rendezvous.
            try {
                downloadFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception expected) {
                //Cualquier excepción es aceptable: el pipe fue abortado por cancel.
            }

            //El estado en BD debe ser CANCELLED.
            Transfer t = transferRepository.findById(transferId).orElseThrow();
            assertThat(t.getStatus()).isEqualTo(Transfer.Status.CANCELLED);
            assertThat(t.getFailureReason()).isEqualTo("Cancelled by user");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("cancel sobre transferencia COMPLETED devuelve 409 TRANSFER_STATE_CONFLICT")
    void cancelOnCompletedReturns409() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            long folderId = createFolder(sender.accessToken(), "/pics", "READ");
            long transferId = createDownloadRequest(receiver, sender.deviceId(), folderId);

            //Marcamos manualmente como COMPLETED para no depender del pipe.
            Transfer t = transferRepository.findById(transferId).orElseThrow();
            t.setStatus(Transfer.Status.COMPLETED);
            transferRepository.save(t);

            ResponseEntity<Map<String, Object>> resp = client().delete()
                    .uri("/api/transfers/" + transferId + "/cancel")
                    .header("Authorization", "Bearer " + receiver.accessToken())
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_STATE_CONFLICT");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) resp.getBody().get("details");
            assertThat(details)
                    .containsEntry("action", "cancel")
                    .containsEntry("status", "COMPLETED");
        }
    }

    @Test
    @DisplayName("cancel sobre transferencia ya CANCELLED devuelve 409 TRANSFER_STATE_CONFLICT")
    void cancelAlreadyCancelledReturns409() throws Exception {
        String email = registerAndVerify();
        LoginResult receiver = loginAsNewDevice(email, "receiver");
        LoginResult sender = loginAsNewDevice(email, "sender");
        try (StompTestClient a = StompTestClient.connect(port, receiver.accessToken());
             StompTestClient b = StompTestClient.connect(port, sender.accessToken())) {
            claimPresence(a, receiver.deviceId());
            claimPresence(b, sender.deviceId());
            waitOnline(receiver.accessToken(), 2);
            long folderId = createFolder(sender.accessToken(), "/pics", "READ");
            long transferId = createDownloadRequest(receiver, sender.deviceId(), folderId);

            //Primer cancel OK.
            ResponseEntity<Map<String, Object>> first = client().delete()
                    .uri("/api/transfers/" + transferId + "/cancel")
                    .header("Authorization", "Bearer " + receiver.accessToken())
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            //Segundo cancel: ya no está PENDING → 409.
            ResponseEntity<Map<String, Object>> second = client().delete()
                    .uri("/api/transfers/" + transferId + "/cancel")
                    .header("Authorization", "Bearer " + receiver.accessToken())
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.getBody()).containsEntry("code", "TRANSFER_STATE_CONFLICT");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) second.getBody().get("details");
            assertThat(details)
                    .containsEntry("action", "cancel")
                    .containsEntry("status", "CANCELLED");
        }
    }

    @Test
    @DisplayName("upload sobre transferId inexistente devuelve 404 TRANSFER_NOT_FOUND")
    void uploadOnUnknownTransferReturns404() throws Exception {
        String email = registerAndVerify();
        LoginResult only = loginAsNewDevice(email, "only");
        try (StompTestClient s = StompTestClient.connect(port, only.accessToken())) {
            claimPresence(s, only.deviceId());
            waitOnline(only.accessToken(), 1);

            ResponseEntity<Map<String, Object>> resp = client().post()
                    .uri("/api/transfers/99999999/upload")
                    .header("Authorization", "Bearer " + only.accessToken())
                    .header("Content-Type", "application/octet-stream")
                    .body(new byte[]{1, 2, 3})
                    .retrieve()
                    .toEntity(MAP_TYPE);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resp.getBody()).containsEntry("code", "TRANSFER_NOT_FOUND");
        }
    }

    // ---------- Helpers ----------

    /**
     * Reclama presencia para {@code deviceId} suscribiendose a su queue RPC. La presencia
     * se registra en el interceptor STOMP al procesar el SUBSCRIBE, no en el CONNECT.
     */
    private void claimPresence(StompTestClient client, Long deviceId) {
        client.session().subscribe("/queue/device." + deviceId, StompTestClient.noopHandler());
    }

    /** Espera a que el usuario tenga {@code expectedDevices} devices y todos online. */
    private void waitOnline(String accessToken, int expectedDevices) {
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            ResponseEntity<Map<String, Object>> resp = getJson("/api/devices", accessToken);
            @SuppressWarnings("unchecked")
            var devs = (java.util.List<Map<String, Object>>) resp.getBody().get("devices");
            assertThat(devs).hasSize(expectedDevices);
            assertThat(devs).allSatisfy(d -> assertThat(d.get("online")).isEqualTo(Boolean.TRUE));
        });
    }

    private long createFolder(String accessToken, String path, String sharing) {
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/folders",
                Map.of("path", path, "sharing", sharing),
                accessToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) resp.getBody().get("id")).longValue();
    }

    private long createDownloadRequest(LoginResult receiver, Long senderDeviceId, long originFolderId) {
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/transfers/download-request",
                Map.of(
                        "senderDeviceId", senderDeviceId,
                        "originFolderId", originFolderId,
                        "originPath", "IMG.jpg",
                        "sizeBytes", 1024),
                receiver.accessToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) resp.getBody().get("transferId")).longValue();
    }

    /** Sube el usuario a PRO (maxDevices=10) para tests que necesitan &gt;2 devices ACTIVE. */
    private void upgradeToPro(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        BillingPlan pro = billingPlanRepository.findByName("PRO").orElseThrow();
        u.setBillingPlan(pro);
        userRepository.save(u);
    }
}
