package baultServer.e2e;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import baultServer.model.BillingPlan;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;
import baultServer.repositorys.UserRepository;
import baultServer.testsupport.AbstractE2ETest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre TODAS las variantes de POST /api/auth/public/login/password y la observabilidad
 * de estados de device desde otro device del mismo usuario:
 * <ol>
 *   <li>Contraseña incorrecta / usuario inexistente -> 401.</li>
 *   <li>Usuario sin verificar correo -> 403 con {@code error:email_not_verified}.</li>
 *   <li>Login sin credenciales de device -> registra un Device nuevo (rawSecret devuelto).</li>
 *   <li>Device BLOCKED -> 403; REMOVED -> re-registro transparente; secreto inválido -> 401;
 *       deviceId inexistente -> 401.</li>
 *   <li>Device DISABLED -> login OK, {@code /api/devices} OK, pero {@code /api/transfers}
 *       y {@code /api/folders} devuelven 403 (falta {@code DEVICE_ACTIVE}).</li>
 *   <li>Otro device observa estados vía GET: DISABLED/BLOCKED visibles, REMOVED da 404.</li>
 * </ol>
 * Para poder tener &gt;2 devices activos en el mismo usuario (FREE::maxDevices=2), sube al
 * usuario al plan PRO antes de registrar los devices.
 */
class LoginE2ETest extends AbstractE2ETest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BillingPlanRepository billingPlanRepository;

    @BeforeEach
    void clean() {
        resetMailbox();
    }

    // ---- 1. Credenciales inválidas -----------------------------------------

    @Test
    @DisplayName("Login con contraseña incorrecta devuelve 401")
    void wrongPasswordReturns401() {
        String email = registerAndVerify();
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", "WrongPass123!"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Login con email inexistente devuelve 401 (mismo status que credenciales malas)")
    void unknownUserReturns401() {
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/auth/public/login/password",
                Map.of("email", "ghost-" + UUID.randomUUID() + "@example.com",
                        "password", "AnyPassword123!"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- 2. Email no verificado --------------------------------------------

    @Test
    @DisplayName("Login antes de verificar email: 403 con body {error:email_not_verified,email}")
    void unverifiedEmailReturns403WithStructuredBody() {
        String email = uniqueEmail();
        //Registro sin confirmar código para dejar user.emailVerified=false.
        postJson("/api/auth/public/register/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD));

        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody())
                .containsEntry("error", "email_not_verified")
                .containsEntry("email", email);
    }

    // ---- 3. Login sin credenciales de device -------------------------------

    @Test
    @DisplayName("Login sin deviceId/deviceSecret registra un Device nuevo y devuelve rawSecret")
    void loginWithoutDeviceCredentialsRegistersNewDevice() {
        String email = registerAndVerify();

        ResponseEntity<Map<String, Object>> first = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD, "alias", "first"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> firstBody = first.getBody();
        assertThat(firstBody).containsKeys("accessToken", "refreshToken", "deviceId", "deviceSecret");
        assertThat((String) firstBody.get("deviceSecret")).isNotBlank();

        //Un segundo login sin credenciales de device tiene que crear un Device DISTINTO
        //(el backend no puede correlacionarlo con el anterior).
        ResponseEntity<Map<String, Object>> second = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD, "alias", "second"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) second.getBody().get("deviceSecret")).isNotBlank();
        assertThat(((Number) second.getBody().get("deviceId")).longValue())
                .as("cada login sin device debe generar un id nuevo")
                .isNotEqualTo(((Number) firstBody.get("deviceId")).longValue());
    }

    // ---- 4. Estados especiales del device ----------------------------------

    @Test
    @DisplayName("Login con device BLOCKED devuelve 403")
    void blockedDeviceLoginReturns403() {
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult observer = loginAsNewDevice(email, "observer");
        LoginResult target = loginAsNewDevice(email, "to-block");

        okOrFail(client().post()
                .uri("/api/devices/" + target.deviceId() + "/block")
                .header("Authorization", "Bearer " + observer.accessToken())
                .retrieve().toBodilessEntity(),
                "block target");

        ResponseEntity<Map<String, Object>> reLogin = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "deviceId", target.deviceId(),
                        "deviceSecret", target.deviceSecret()));
        assertThat(reLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Login con device REMOVED registra un Device nuevo transparentemente")
    void removedDeviceLoginReRegistersTransparently() {
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult observer = loginAsNewDevice(email, "observer");
        LoginResult target = loginAsNewDevice(email, "to-remove");

        okOrFail(client().delete()
                .uri("/api/devices/" + target.deviceId())
                .header("Authorization", "Bearer " + observer.accessToken())
                .retrieve().toBodilessEntity(),
                "remove target");

        ResponseEntity<Map<String, Object>> reLogin = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "deviceId", target.deviceId(),
                        "deviceSecret", target.deviceSecret()));
        assertThat(reLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        long newDeviceId = ((Number) reLogin.getBody().get("deviceId")).longValue();
        assertThat(newDeviceId)
                .as("REMOVED debe forzar el registro de un Device nuevo")
                .isNotEqualTo(target.deviceId());
        assertThat((String) reLogin.getBody().get("deviceSecret")).isNotBlank();
    }

    @Test
    @DisplayName("Login con deviceId correcto pero secreto inválido devuelve 401")
    void invalidDeviceSecretReturns401() {
        String email = registerAndVerify();
        LoginResult target = loginAsNewDevice(email, "target");

        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "deviceId", target.deviceId(),
                        "deviceSecret", "not-the-real-secret-" + UUID.randomUUID()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Login con secreto válido de otro device pero deviceId inexistente devuelve 401")
    void validSecretButUnknownDeviceIdReturns401() {
        String email = registerAndVerify();
        LoginResult target = loginAsNewDevice(email, "target");

        //Reutiliza el secreto real del device conocido, pero apunta a un id inventado.
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "deviceId", 9_999_999L,
                        "deviceSecret", target.deviceSecret()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- 5. DISABLED: login OK, devices OK, transfers/folders 403 ----------

    @Test
    @DisplayName("Device DISABLED: login OK, /api/devices OK, /api/transfers y /api/folders 403")
    void disabledDeviceCanListDevicesButNotTransfersOrFolders() {
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult observer = loginAsNewDevice(email, "observer");
        LoginResult target = loginAsNewDevice(email, "to-disable");

        okOrFail(client().post()
                .uri("/api/devices/" + target.deviceId() + "/deactivate")
                .header("Authorization", "Bearer " + observer.accessToken())
                .retrieve().toBodilessEntity(),
                "deactivate target");

        //Re-login del target: DISABLED sigue permitiendo obtener tokens frescos.
        ResponseEntity<Map<String, Object>> reLogin = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "deviceId", target.deviceId(),
                        "deviceSecret", target.deviceSecret()));
        assertThat(reLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = (String) reLogin.getBody().get("accessToken");

        assertThat(statusOf("/api/devices", access))
                .as("DISABLED debe poder listar sus devices")
                .isEqualTo(HttpStatus.OK);
        assertThat(statusOf("/api/transfers", access))
                .as("DISABLED no debe poder acceder a transfers")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf("/api/folders", access))
                .as("DISABLED no debe poder acceder a folders")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- 6. Observador ve estados de otros devices por GET ------------------

    @Test
    @DisplayName("Otro device ve por GET estados DISABLED/BLOCKED y 404 en el detalle del REMOVED")
    void otherDeviceObservesLifecycle() {
        String email = registerAndVerify();
        upgradeToPro(email);
        LoginResult observer = loginAsNewDevice(email, "observer");
        LoginResult toDisable = loginAsNewDevice(email, "to-disable");
        LoginResult toBlock = loginAsNewDevice(email, "to-block");
        LoginResult toRemove = loginAsNewDevice(email, "to-remove");

        postAsDevice("/api/devices/" + toDisable.deviceId() + "/deactivate", observer.accessToken());
        postAsDevice("/api/devices/" + toBlock.deviceId() + "/block", observer.accessToken());
        deleteAsDevice("/api/devices/" + toRemove.deviceId(), observer.accessToken());

        // Detalle: los estados vivos son visibles con su status; el REMOVED da 404.
        assertThat(deviceDetailStatus(observer.accessToken(), toDisable.deviceId()))
                .isEqualTo("DISABLED");
        assertThat(deviceDetailStatus(observer.accessToken(), toBlock.deviceId()))
                .isEqualTo("BLOCKED");
        assertThat(statusOf("/api/devices/" + toRemove.deviceId(), observer.accessToken()))
                .as("REMOVED debe presentarse como 'no existe'")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Listado: los dos vivos aparecen con su status correcto.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) getJson(
                "/api/devices", observer.accessToken()).getBody().get("devices");
        assertThat(statusInList(devices, toDisable.deviceId())).isEqualTo("DISABLED");
        assertThat(statusInList(devices, toBlock.deviceId())).isEqualTo("BLOCKED");
    }

    // ---- helpers -----------------------------------------------------------

    /** Cambia el plan del usuario a PRO (maxDevices=10) para poder registrar &gt;2 ACTIVE. */
    private void upgradeToPro(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        BillingPlan pro = billingPlanRepository.findByName("PRO").orElseThrow();
        u.setBillingPlan(pro);
        userRepository.save(u);
    }

    private HttpStatusCode statusOf(String path, String token) {
        return client().get()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
    }

    private String deviceDetailStatus(String token, Long deviceId) {
        ResponseEntity<Map<String, Object>> resp = getJson("/api/devices/" + deviceId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return String.valueOf(resp.getBody().get("status"));
    }

    private String statusInList(List<Map<String, Object>> devices, Long id) {
        return devices.stream()
                .filter(d -> ((Number) d.get("id")).longValue() == id)
                .findFirst()
                .map(d -> String.valueOf(d.get("status")))
                .orElseThrow(() -> new AssertionError("Device " + id + " no está en la lista"));
    }

    private void postAsDevice(String path, String token) {
        okOrFail(client().post()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .retrieve().toBodilessEntity(), "POST " + path);
    }

    private void deleteAsDevice(String path, String token) {
        okOrFail(client().delete()
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .retrieve().toBodilessEntity(), "DELETE " + path);
    }

    private static void okOrFail(ResponseEntity<Void> resp, String label) {
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(label + " -> " + resp.getStatusCode());
        }
    }
}
