package baultServer.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Id;

@Entity
@Data
public class User {
    public enum Role {
        USER, ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    private String firstname;
    private String lastName;
    private LocalDate birthDate;
    private String email;

    private ZonedDateTime createdAt;
    private String passwordHash;

    private String roles;
    private boolean enabled;

    public boolean hasRole(Role role) {
        String roleName = role.name();
        return Arrays.asList(roles.split(",")).contains(roleName);
    }
}
