package baultServer.e2e;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import baultServer.testsupport.AbstractE2ETest;
import baultServer.testsupport.RecordingEmailService.Sent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo end-to-end de registro → verificación de email por código → login.
 * Todo va contra HTTP real en un Tomcat embebido; el envío de correos está sustituido
 * por {@link baultServer.testsupport.RecordingEmailService}.
 */
class AuthFlowE2ETest extends AbstractE2ETest {

    @BeforeEach
    void cleanMailbox() {
        resetMailbox();
    }

    @Test
    @DisplayName("Smoke: el contexto de test arranca sin depender de RESEND_API_KEY ni de red")
    void contextLoadsWithMockedEmail() {
        // Si llega hasta aquí es que Spring levantó el contexto, RecordingEmailService
        // sustituyó a ResendEmailService, y Tomcat está escuchando en un puerto aleatorio.
        assertThat(port).isPositive();
        assertThat(emails.all()).isEmpty();
    }

    @Test
    @DisplayName("Register: devuelve 202 y encola un correo de verificación con un código de 6 dígitos")
    void registerSendsVerificationEmail() {
        String email = uniqueEmail();

        ResponseEntity<Map<String, Object>> response = postJson(
                "/api/auth/public/register/password",
                Map.of("email", email, "password", "SuperSecret123!"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody())
                .containsEntry("email", email)
                .containsEntry("message", "verification_email_sent");

        assertThat(emails.all()).hasSize(1);
        Sent sent = emails.lastSentTo(email).orElseThrow();
        assertThat(sent.subject()).contains("Verifica");
        assertThat(emails.lastCodeFor(email)).isPresent()
                .get().asString().matches("\\d{6}");
    }

    @Test
    @DisplayName("Login antes de verificar el email devuelve 403 con error 'email_not_verified'")
    void loginBeforeVerificationIsRejected() {
        String email = uniqueEmail();
        String password = "SuperSecret123!";
        postJson("/api/auth/public/register/password",
                Map.of("email", email, "password", password));

        ResponseEntity<Map<String, Object>> response = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", password));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("error", "email_not_verified")
                .containsEntry("email", email);
    }

    @Test
    @DisplayName("Flujo completo: register → confirm código → login devuelve tokens y device")
    void fullRegisterVerifyLoginFlow() {
        String email = uniqueEmail();
        String password = "SuperSecret123!";

        // 1. Registro
        ResponseEntity<Map<String, Object>> register = postJson(
                "/api/auth/public/register/password",
                Map.of("email", email, "password", password));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 2. Recuperamos el código del "correo" recibido
        String code = emails.lastCodeFor(email).orElseThrow(
                () -> new AssertionError("No se recibió correo con código para " + email));

        // 3. Confirmación del email
        ResponseEntity<Map<String, Object>> confirm = postJson(
                "/api/auth/public/email/verify/confirm",
                Map.of("email", email, "code", code));
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 4. Login ya debe funcionar y devolver el paquete completo (primer login ⇒ deviceSecret presente)
        ResponseEntity<Map<String, Object>> login = postJson(
                "/api/auth/public/login/password",
                Map.of("email", email, "password", password));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = login.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("accessToken", "refreshToken", "deviceId", "deviceSecret", "tokenType");
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat((String) body.get("accessToken")).isNotBlank();
        assertThat((String) body.get("refreshToken")).isNotBlank();
        assertThat((String) body.get("deviceSecret")).isNotBlank();
    }
}
