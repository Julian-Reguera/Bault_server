package baultServer.model;

import java.time.ZonedDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Device {
    @Id
    private Long id;

    private Boolean enabled;
    private ZonedDateTime createdAt;
    private ZonedDateTime lastConnection;
    private String operatingSystem;
    private String appVersion;
    private boolean trusted; 
}
