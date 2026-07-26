package baultServer.model;

import java.time.ZonedDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import lombok.Data;

@Entity
@Data
public class Transfer {
    public enum Status {
        PENDING, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String originPath;
    private String destinationPath;
    private Long size;
    private ZonedDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_device_id")
    private Device sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_device_id")
    private Device receiver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private Folder folder;
}
