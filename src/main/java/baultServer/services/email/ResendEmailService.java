package baultServer.services.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

import baultServer.exceptions.EmailDeliveryException;

@Service
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final Resend resend;
    private final String from;

    public ResendEmailService(
            @Value("${bault.email.resend.api-key}") String apiKey,
            @Value("${bault.email.from}") String from) {
        this.resend = new Resend(apiKey);
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .html(htmlBody)
                .text(textBody)
                .build();
        try {
            CreateEmailResponse response = resend.emails().send(options);
            log.info("Sent email via Resend id={} to={}", response.getId(), to);
        } catch (ResendException e) {
            log.error("Resend delivery failed to={} subject={}", to, subject, e);
            throw new EmailDeliveryException("Email delivery failed", e);
        }
    }
}
