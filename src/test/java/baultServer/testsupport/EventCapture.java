package baultServer.testsupport;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.awaitility.Awaitility.await;

/**
 * Handler STOMP que acumula todos los frames recibidos y permite a los tests
 * esperar la aparición de un evento concreto que cumpla un predicado.
 * <p>
 * Usado para las suscripciones a {@code /user/queue/events} en los tests E2E.
 */
public class EventCapture implements StompFrameHandler {

    private final CopyOnWriteArrayList<JsonNode> received = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;

    public EventCapture(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        try {
            received.add(mapper.readTree((byte[]) payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void clear() {
        received.clear();
    }

    public int size() {
        return received.size();
    }

    /**
     * Espera hasta {@code timeoutSeconds} a que llegue al menos un evento que cumpla
     * el predicado. Devuelve la primera coincidencia. Falla el test si vence el timeout.
     */
    public JsonNode awaitOne(Predicate<JsonNode> matcher, long timeoutSeconds) {
        await().atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(30, TimeUnit.MILLISECONDS)
                .until(() -> received.stream().anyMatch(matcher));
        return findFirst(matcher).orElseThrow();
    }

    /** Comprueba (sin esperar) si ya hay algún evento que cumpla el predicado. */
    public boolean has(Predicate<JsonNode> matcher) {
        return received.stream().anyMatch(matcher);
    }

    private Optional<JsonNode> findFirst(Predicate<JsonNode> matcher) {
        for (JsonNode ev : received) if (matcher.test(ev)) return Optional.of(ev);
        return Optional.empty();
    }
}
