package baultServer.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.ObjectMapper;

/**
 * Configuración específica de los tests E2E. Importada desde {@link AbstractE2ETest}.
 * Registra {@link RecordingEmailService} como bean {@code @Primary}, de modo que
 * cualquier inyección de {@link baultServer.services.email.EmailService} en el contexto
 * de test recibe el doble en memoria en lugar del {@code ResendEmailService} real.
 */
@TestConfiguration
public class E2ETestConfig {

    @Bean
    @Primary
    public RecordingEmailService recordingEmailService() {
        return new RecordingEmailService();
    }

    // JacksonAutoConfiguration no se está aplicando en el contexto de test (los starters
    // granulares de Boot 4.x no arrastran spring-boot-starter-json). Registramos un
    // ObjectMapper mínimo para que TransferHistoryService pueda inyectarlo.
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
