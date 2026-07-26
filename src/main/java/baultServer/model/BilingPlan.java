package baultServer.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class BilingPlan {
    @Id
    private Long id;

    private int maxSpeed;
    private int maxTrafic;
    private int maxDevices;
    private Double mensualPrice;
    private Double annualPrice;
    private boolean enabled; 
}
