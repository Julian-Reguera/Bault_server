package baultServer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BaultServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BaultServerApplication.class, args);
	}

}
