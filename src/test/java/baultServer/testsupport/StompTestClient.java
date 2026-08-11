package baultServer.testsupport;

import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Cliente STOMP para tests: encapsula {@link WebSocketStompClient},
 * el scheduler de heartbeat, y la {@link StompSession} viva.
 * Cierra los tres recursos limpiamente en {@link #close()}.
 * <p>
 * Usa el heartbeat rápido del perfil {@code test} (500ms) para que las sesiones
 * muertas se liberen rápido y no interfieran entre tests consecutivos.
 */
public final class StompTestClient implements AutoCloseable {

    private final WebSocketStompClient client;
    private final ThreadPoolTaskScheduler scheduler;
    private final StompSession session;

    private StompTestClient(WebSocketStompClient client,
                            ThreadPoolTaskScheduler scheduler,
                            StompSession session) {
        this.client = client;
        this.scheduler = scheduler;
        this.session = session;
    }

    public static StompTestClient connect(int port, String jwt)
            throws InterruptedException, ExecutionException, TimeoutException {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-stomp-hb-");
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        client.setDefaultHeartbeat(new long[]{500, 500});

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Authorization", "Bearer " + jwt);

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/api/ws",
                handshakeHeaders,
                new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        return new StompTestClient(client, scheduler, session);
    }

    public StompSession session() {
        return session;
    }

    /** Handler no-op para suscripciones que sólo necesitan existir (p. ej. reclamar presencia). */
    public static StompFrameHandler noopHandler() {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { /* noop */ }
        };
    }

    @Override
    public void close() {
        try { if (session.isConnected()) session.disconnect(); } catch (Exception ignored) {}
        try { client.stop(); } catch (Exception ignored) {}
        try { scheduler.shutdown(); } catch (Exception ignored) {}
    }
}
