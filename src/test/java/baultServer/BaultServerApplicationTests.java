package baultServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import baultServer.testsupport.E2ETestConfig;

// El perfil "test" evita depender de la variable de entorno RESEND_API_KEY
// (que exige application-dev.properties) y usa H2 en memoria.
@SpringBootTest
@ActiveProfiles("test")
@Import(E2ETestConfig.class)
class BaultServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
