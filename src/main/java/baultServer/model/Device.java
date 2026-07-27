package baultServer.model;

import java.time.ZonedDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_device_user", columnList = "user_id")
})
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    private boolean enabled;
    private ZonedDateTime createdAt;
    private ZonedDateTime lastConnection;
    private String operatingSystem;
    private String appVersion;
    private boolean trusted;

    private String alias;

    @Column(nullable = false, length = 64)
    private String secretHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "device")
    private List<Folder> ownedFolders;

    @OneToMany(mappedBy = "sender")
    private List<Transfer> sentTransfers;

    @OneToMany(mappedBy = "receiver")
    private List<Transfer> receivedTransfers;
}
