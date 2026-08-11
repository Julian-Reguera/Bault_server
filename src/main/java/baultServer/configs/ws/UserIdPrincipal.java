package baultServer.configs.ws;

import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Principal usado en las sesiones WebSocket. Su {@code getName()} devuelve el
 * userId como String — así {@code convertAndSendToUser(String.valueOf(userId), ...)}
 * hace fan-out a todas las sesiones del mismo usuario sin depender del email.
 * <p>
 * Extiende {@link AbstractAuthenticationToken} (que implementa {@code Authentication}
 * — subinterfaz de {@code Principal}) porque {@code @EnableWebSocketSecurity} exige
 * un objeto de tipo Authentication, no un Principal raw, para las reglas
 * {@code authenticated()} del {@code AuthorizationManager}.
 * <p>
 * El email se guarda para trazabilidad, pero no se usa como ruta de mensajería.
 */
public class UserIdPrincipal extends AbstractAuthenticationToken {

    private final Long userId;
    private final String email;

    public UserIdPrincipal(Long userId, String email) {
        //Sin authorities: los matchers usados son authenticated()/denyAll(), no basados en roles.
        super(Collections.emptyList());
        this.userId = userId;
        this.email = email;
        setAuthenticated(true);
    }

    public Long userId() { return userId; }
    public String email() { return email; }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    /** Devuelve el propio Principal — no manejamos credenciales adicionales. */
    @Override
    public Object getPrincipal() {
        return this;
    }

    /** JWT ya validado en el handshake; no expone la credencial. */
    @Override
    public Object getCredentials() {
        return null;
    }
}
