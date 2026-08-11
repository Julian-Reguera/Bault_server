package baultServer.testsupport;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Base para todos los tests E2E. Levanta el contexto Spring completo en un puerto
 * aleatorio (Tomcat real) y expone helpers para hablar HTTP contra {@code localhost}.
 * <p>
 * Todos los tests que hereden de esta clase comparten el mismo {@code ApplicationContext}
 * cacheado (mismo conjunto de anotaciones ⇒ Spring reutiliza el contexto), así que el
 * arranque de Tomcat sólo se paga una vez por ejecución de la suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(E2ETestConfig.class)
public abstract class AbstractE2ETest {

    // Tipo de retorno reutilizable para respuestas JSON deserializadas a Map.
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    protected static final String DEFAULT_TEST_PASSWORD = "SuperSecret123!";

    @LocalServerPort
    protected int port;

    @Autowired
    protected RecordingEmailService emails;

    protected RestClient client;

    protected RestClient client() {
        if (client == null) {
            // No lanza sobre 4xx/5xx: los tests inspeccionan el status directamente.
            client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .defaultStatusHandler(status -> true, (req, res) -> {})
                    .build();
        }
        return client;
    }

    /**
     * Limpia el buzón entre tests para que las aserciones sobre "último correo enviado"
     * no dependan del orden de ejecución.
     */
    protected void resetMailbox() {
        emails.clear();
    }

    /**
     * Genera un email único por test para no depender de la limpieza de la BD.
     * Cada test opera sobre un usuario nuevo con un email aleatorio.
     */
    protected String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@example.com";
    }

    protected ResponseEntity<Map<String, Object>> postJson(String path, Map<String, ?> body) {
        return client().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(MAP_TYPE);
    }

    protected ResponseEntity<Map<String, Object>> postJson(String path, Map<String, ?> body, String accessToken) {
        return client().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .body(body)
                .retrieve()
                .toEntity(MAP_TYPE);
    }

    protected ResponseEntity<Map<String, Object>> getJson(String path, String accessToken) {
        return client().get()
                .uri(path)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toEntity(MAP_TYPE);
    }

    // -----------------------------------------------------------------------
    //  Helpers de alto nivel: reutilizables desde cualquier clase de test E2E.
    // -----------------------------------------------------------------------

    /**
     * Registra un usuario nuevo con email aleatorio y contraseña por defecto, y le
     * confirma el email consumiendo el código del correo mockeado.
     * Deja al usuario en estado "puede hacer login" pero sin device todavía.
     */
    protected String registerAndVerify() {
        String email = uniqueEmail();
        ResponseEntity<Map<String, Object>> register = postJson(
                "/api/auth/public/register/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD));
        if (!register.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Register failed: " + register.getStatusCode() + " " + register.getBody());
        }
        String code = emails.lastCodeFor(email).orElseThrow(
                () -> new AssertionError("No verification code received for " + email));
        ResponseEntity<Map<String, Object>> confirm = postJson(
                "/api/auth/public/email/verify/confirm",
                Map.of("email", email, "code", code));
        if (!confirm.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Verify failed: " + confirm.getStatusCode() + " " + confirm.getBody());
        }
        return email;
    }

    /**
     * Hace login como un dispositivo nuevo (sin deviceId/deviceSecret previos), lo que
     * fuerza al backend a registrar un Device nuevo y devolver su rawSecret. Ideal para
     * simular "esta es la primera vez que este cliente se conecta".
     */
    protected LoginResult loginAsNewDevice(String email, String alias) {
        ResponseEntity<Map<String, Object>> response = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", DEFAULT_TEST_PASSWORD,
                        "alias", alias == null ? "test-device" : alias));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Login failed: " + response.getStatusCode() + " " + response.getBody());
        }
        Map<String, Object> body = response.getBody();
        if (body == null) throw new AssertionError("Empty login response");
        return new LoginResult(
                email,
                (String) body.get("accessToken"),
                (String) body.get("refreshToken"),
                ((Number) body.get("deviceId")).longValue(),
                (String) body.get("deviceSecret"));
    }

    /**
     * Atajo: registra + verifica + login como device nuevo, todo en uno.
     */
    protected LoginResult registerVerifyAndLogin(String alias) {
        String email = registerAndVerify();
        return loginAsNewDevice(email, alias);
    }

    /**
     * Resultado de un login: expone todo lo que un cliente necesita para
     * autenticarse en llamadas posteriores (HTTP y WebSocket).
     */
    public record LoginResult(String email, String accessToken, String refreshToken,
                              Long deviceId, String deviceSecret) {}
}
