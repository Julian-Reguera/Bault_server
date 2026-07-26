package baultServer.controllers;

import java.time.ZonedDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import baultServer.model.User;
import baultServer.repositorys.UserRepository;
import baultServer.services.JwtService;
import baultServer.services.RefreshTokenService;
import baultServer.services.RefreshTokenService.Rotated;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthenticationManager authManager,
                          UserDetailsService userDetailsService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenService refreshTokenService) {
        this.authManager = authManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/login/password")
    public ResponseEntity<ObjectNode> login(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        String password = requireText(body, "password");

        authManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
        UserDetails principal = userDetailsService.loadUserByUsername(email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        String access = jwtService.generateToken(principal);
        String refresh = refreshTokenService.issue(user);
        return ResponseEntity.ok(bearer(access, refresh));
    }

    @PostMapping("/register/password")
    public ResponseEntity<ObjectNode> register(@RequestBody JsonNode body) {
        String email = requireText(body, "email");
        String password = requireText(body, "password");
        String firstName = optionalText(body, "firstName");
        String lastName = optionalText(body, "lastName");

        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(CONFLICT, "Email already registered");
        }

        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRoles("USER");
        u.setEnabled(true);
        u.setCreatedAt(ZonedDateTime.now());
        userRepository.save(u);

        UserDetails principal = userDetailsService.loadUserByUsername(email);
        String access = jwtService.generateToken(principal);
        String refresh = refreshTokenService.issue(u);
        return ResponseEntity.ok(bearer(access, refresh));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ObjectNode> refresh(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        Rotated rotated = refreshTokenService.rotate(refreshToken);
        UserDetails principal = userDetailsService.loadUserByUsername(rotated.user().getEmail());
        String access = jwtService.generateToken(principal);
        return ResponseEntity.ok(bearer(access, rotated.rawToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody JsonNode body) {
        String refreshToken = requireText(body, "refreshToken");
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        refreshTokenService.revokeAllForUser(user);
        return ResponseEntity.noContent().build();
    }

    private static ObjectNode bearer(String accessToken, String refreshToken) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("accessToken", accessToken);
        node.put("refreshToken", refreshToken);
        node.put("tokenType", "Bearer");
        return node;
    }

    private static String requireText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing or invalid field: " + field);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return (node == null || node.isNull() || !node.isTextual()) ? null : node.asText();
    }
}
