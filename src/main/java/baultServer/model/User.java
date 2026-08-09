package baultServer.model;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import lombok.Data;

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

    private String firstName;
    private String lastName;
    private LocalDate birthDate;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    private ZonedDateTime createdAt;

    @Column(nullable = false)
    private String passwordHash;

    private String roles;
    private boolean enabled;
    private boolean emailVerified;

    public boolean hasRole(Role role) {
        String roleName = role.name();
        return Arrays.asList(roles.split(",")).contains(roleName);
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_plan_id")
    private BillingPlan billingPlan;

    //Atributos "de la relacion" user<->plan: fecha alta del plan actual y ultimo pago.
    //Nulables porque un user puede existir sin plan (o sin haber pagado aun).
    private ZonedDateTime planSubscribedAt;
    private ZonedDateTime planLastPaymentAt;

    @OneToMany(mappedBy = "user")
    private List<Device> linkedDevices;

    
}
