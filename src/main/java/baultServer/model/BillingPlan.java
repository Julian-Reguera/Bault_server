package baultServer.model;

import java.util.List;

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

    private int maxSpeed;
    private int maxTraffic;
    private int maxDevices;
    private int maxConcurrentTransfers;
    private int monthlyPrice; //dinero en centimos (evita redondeos)
    private int annualPrice;  //dinero en centimos (evita redondeos)
    private boolean enabled;

    @OneToMany(mappedBy = "billingPlan")
    private List<User> users;
}
