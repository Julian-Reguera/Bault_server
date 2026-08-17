package baultServer.model;

import java.time.ZonedDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_transfer_status_created", columnList = "status,createdAt"),
        @Index(name = "idx_transfer_owner_idem", columnList = "owner_device_id,idempotencyKey")
})
public class Transfer {
    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, DENIED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String originPath;
    private String destinationPath;
    private Long sizeBytes;
    private ZonedDateTime createdAt;
    private ZonedDateTime startedAt;
    private ZonedDateTime completedAt;

    @Column(length = 500)
    private String failureReason;

    @Column(length = 100)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_device_id")
    private Device sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_device_id")
    private Device receiver;

    /**
     * Device que inició la transferencia. Coincide con sender (upload-request), con receiver
     * (download-request) o con un tercer device del mismo usuario (third-party-request).
     * En este último caso {@code owner != sender && owner != receiver}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_device_id", nullable = false)
    private Device owner;

    /**
     * Carpeta compartida del sender de la que sale el archivo. Se rellena en download-request
     * y en third-party-request; null en upload-request.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_folder_id")
    private Folder originFolder;

    /**
     * Carpeta compartida del receiver donde se deja el archivo. Se rellena en upload-request
     * y en third-party-request; null en download-request.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_folder_id")
    private Folder destinationFolder;
}
