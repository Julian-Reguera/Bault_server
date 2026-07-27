package baultServer.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        messages
                //Los clientes NO pueden mandar SEND directo al broker
                .simpMessageDestMatchers("/topic/**", "/queue/**", "/user/**").denyAll()
                //Los clientes SI pueden mandar SEND a los controladores /app/**
                .simpMessageDestMatchers("/app/**").authenticated()
                //SUBSCRIBE a canales del broker requiere autenticación
                .simpSubscribeDestMatchers("/user/**", "/topic/**", "/queue/**").authenticated()
                //Frames de control (CONNECT, DISCONNECT, HEARTBEAT...) requieren autenticación
                .nullDestMatcher().authenticated()
                //Todo lo demás: denegado
                .anyMessage().denyAll();
        return messages.build();
    }

    //JWT va en el header Authorization en el handshake, no usamos cookies:
    //deshabilitamos el CsrfChannelInterceptor que @EnableWebSocketSecurity añade por defecto.
    @Bean
    ChannelInterceptor csrfChannelInterceptor() {
        return new ChannelInterceptor() {};
    }
}
