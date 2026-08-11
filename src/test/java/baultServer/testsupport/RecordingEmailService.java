package baultServer.testsupport;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import baultServer.services.email.EmailService;

/**
 * Sustituye a {@link baultServer.services.email.ResendEmailService} en los tests.
 * No envía nada por red: guarda cada mensaje en memoria para que los tests puedan
 * inspeccionarlos y extraer el código de verificación.
 */
public class RecordingEmailService implements EmailService {

    // Regex del código: la plantilla usa 6 dígitos (bault.email.verification.code-length).
    // Se busca en el cuerpo de texto plano donde el código aparece en su propia línea.
    private static final Pattern CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        sent.add(new Sent(to, subject, htmlBody, textBody));
    }

    public List<Sent> all() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    /**
     * Devuelve el último correo enviado a {@code email}, o vacío si no hay ninguno.
     */
    public Optional<Sent> lastSentTo(String email) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            Sent s = sent.get(i);
            if (s.to().equalsIgnoreCase(email)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Extrae el código numérico del último correo enviado a {@code email}.
     * Útil para el flujo de verificación de email y reset de contraseña.
     */
    public Optional<String> lastCodeFor(String email) {
        return lastSentTo(email).flatMap(s -> {
            Matcher m = CODE_PATTERN.matcher(s.text());
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        });
    }

    public record Sent(String to, String subject, String html, String text) {}
}
