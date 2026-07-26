package baultServer.model;

import java.time.ZonedDateTime;

import jakarta.persistence.Entity;
import lombok.Data;

@Entity
@Data
public class Transfer {
    public enum Status {
        PENDING, COMPLETED, FAILED
    }

    private Long id;

    private Status state;
    private String originPath;
    private String destinationPath;
    private Long size;
    private ZonedDateTime date; 
}
