package baultServer.model;

import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import lombok.Data;

@Entity
@Data
public class BillingPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    private int maxSpeed;    //Mbps, unificado subida y bajada (ambos dispositivos son del mismo user)
    private int maxTraffic;
    private int maxDevices;
    private int maxConcurrentTransfers;
    private int monthlyPrice; //dinero en centimos (evita redondeos)
    private int annualPrice;  //dinero en centimos (evita redondeos)
    private boolean encryptedFoldersIncluded;
    private boolean enabled;

    @OneToMany(mappedBy = "billingPlan")
    private List<User> users;
}
