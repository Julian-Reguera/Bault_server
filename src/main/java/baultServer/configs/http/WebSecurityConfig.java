package baultServer.configs.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class WebSecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtAuthFilter,
                                               JsonAuthenticationEntryPoint jsonEntryPoint) throws Exception {
        http
            .securityMatcher("/api/**") //Determina para que rutas aplican las configuraciones que siguen
            .csrf(csrf -> csrf.disable()) //deshabilitación de CSRF para que no se requiera token CSRF en las peticiones (no hay formularios en una web)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) //dehabilitación de cookie de sesión y sesión (autentificación en cada petición)
            .formLogin(f -> f.disable()) //Elimina el endpoint POST Login por defecto de Spring Security
            .httpBasic(b -> b.disable()) //Elimina la opcion de autentificarse en cada peticion con en los argumentos (Authorization: Basic <base64(username:password)>)
            .logout(l -> l.disable())    //Elimina el Logout clásico ya  que no hay sesión
            .exceptionHandling(e -> e.authenticationEntryPoint(jsonEntryPoint)) //401 en formato ApiErrorResponse cuando falta o falla la autenticación
            .authorizeHttpRequests(authorize -> authorize //Permite editar el filtro authorize que determinan reglas para autorizar al usuario a acceder a diferentes rutas
                .requestMatchers("/api/auth/public/**").permitAll() //Endpoints que emiten credenciales o funcionan con el access token expirado (login, register, refresh, reset password, logout por posesión del refresh)
                .requestMatchers("/api/auth/secured/**").authenticated() //Endpoints de auth que requieren JWT válido (logout-all). El email verificado es implícito porque login lo exige.
                .requestMatchers("/api/devices/**").authenticated() //Cualquier dispositivo (ACTIVE o DISABLED) puede consultar/gestionar sus devices
                .requestMatchers("/api/transfers/**").hasAuthority(JwtAuthenticationFilter.AUTH_DEVICE_ACTIVE) //Solo dispositivos ACTIVE pueden operar transfers
                .requestMatchers("/api/folders/**").hasAuthority(JwtAuthenticationFilter.AUTH_DEVICE_ACTIVE) //Solo dispositivos ACTIVE pueden ver/manipular folders
                .anyRequest().authenticated() //Resto de la API: basta con estar autenticado.
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
