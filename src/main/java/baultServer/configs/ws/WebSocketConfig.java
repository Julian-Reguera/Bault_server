package baultServer.configs.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final UserIdHandshakeHandler userIdHandshakeHandler;
    private final long serverHeartbeatMs;
    private final long clientHeartbeatMs;

    public WebSocketConfig(JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           StompAuthChannelInterceptor stompAuthChannelInterceptor,
                           UserIdHandshakeHandler userIdHandshakeHandler,
                           @Value("${bault.ws.heartbeat.server-ms:10000}") long serverHeartbeatMs,
                           @Value("${bault.ws.heartbeat.client-ms:10000}") long clientHeartbeatMs) {
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.userIdHandshakeHandler = userIdHandshakeHandler;
        this.serverHeartbeatMs = serverHeartbeatMs;
        this.clientHeartbeatMs = clientHeartbeatMs;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setHandshakeHandler(userIdHandshakeHandler)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        //Server y cliente envían un ping cada N ms si no hay tráfico.
        //Si no se recibe pong en ~2 intervalos, la sesión se cierra.
        //Valor configurable en application-*.properties (default 10s en dev/prod, más bajo en test).
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{serverHeartbeatMs, clientHeartbeatMs})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

    private ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
