package baultServer.model;

import java.time.ZonedDateTime;
import java.util.List;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_device_user", columnList = "user_id")
})
public class Device implements Transferable<Device.Transfer>{

    public enum Status {
        ACTIVE,
        DISABLED,
        BLOCKED,
        REMOVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private ZonedDateTime createdAt;
    private ZonedDateTime lastConnection;
    private ZonedDateTime lastActivatedAt;
    private String operatingSystem;
    private String appVersion;

    private String alias;

    @Column(nullable = false, length = 64)
    private String secretHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "device")
    private List<Folder> ownedFolders;

    @OneToMany(mappedBy = "sender")
    private List<baultServer.model.Transfer> sentTransfers;

    @OneToMany(mappedBy = "receiver")
    private List<baultServer.model.Transfer> receivedTransfers;

    @Override
    public Transfer toTransfer() {
        return new Transfer(id, alias, operatingSystem, appVersion, lastConnection, status, lastActivatedAt, false);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class Transfer {
        private Long id;
        private String alias;
        private String operatingSystem;
        private String appVersion;
        private ZonedDateTime lastConnection;
        private Status status;
        private ZonedDateTime lastActivatedAt;
        private boolean online;
    }
}
