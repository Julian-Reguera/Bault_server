package baultServer.model;

import java.time.ZonedDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class BugReport {
    public enum Status {
        OPEN,REVISION, CLOSED
    }
    @Id
    private Long id;

    private String title;
    private String message;
    private ZonedDateTime createdAt;
    private Status state;
}
