package baultServer.configs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.DeviceRepository;
import baultServer.repositorys.UserRepository;
import baultServer.services.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String DEVICE_ID_ATTR = "bault.deviceId";

    public static final String AUTH_DEVICE_ACTIVE = "DEVICE_ACTIVE";
    public static final String AUTH_DEVICE_DISABLED = "DEVICE_DISABLED";
    public static final String AUTH_EMAIL_VERIFIED = "EMAIL_VERIFIED";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserDetailsService userDetailsService,
                                   DeviceRepository deviceRepository,
                                   UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            String username = jwtService.extractUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails user = userDetailsService.loadUserByUsername(username);
                if (user.isEnabled() && jwtService.isValid(token, user)) {
                    Long deviceId = jwtService.extractDeviceId(token);
                    Collection<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());

                    User domainUser = userRepository.findByEmail(username).orElse(null);
                    if (domainUser != null && domainUser.isEmailVerified()) {
                        authorities.add(new SimpleGrantedAuthority(AUTH_EMAIL_VERIFIED));
                    }

                    if (deviceId != null) {
                        Device device = deviceRepository.findById(deviceId).orElse(null);
                        if (device != null) {
                            Device.Status status = device.getStatus();
                            if (status == Device.Status.BLOCKED || status == Device.Status.REMOVED) {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Device " + status);
                                return;
                            }
                            if (status == Device.Status.ACTIVE) {
                                authorities.add(new SimpleGrantedAuthority(AUTH_DEVICE_ACTIVE));
                            } else if (status == Device.Status.DISABLED) {
                                authorities.add(new SimpleGrantedAuthority(AUTH_DEVICE_DISABLED));
                            }
                        }
                        request.setAttribute(DEVICE_ID_ATTR, deviceId);
                    }

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user, null, authorities);
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // Invalid token: leave context anonymous; downstream will 401.
        }

        chain.doFilter(request, response);
    }
}
