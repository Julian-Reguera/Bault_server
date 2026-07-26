package baultServer.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
public class FolderShare {
    public enum Permission {
        READ, WRITE
    }

    @EmbeddedId
    private FolderShareId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("deviceId")
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("folderId")
    @JoinColumn(name = "folder_id")
    private Folder folder;

    @NotEmpty
    @ElementCollection(targetClass = Permission.class)
    @CollectionTable(
        name = "folder_share_permission",
        joinColumns = {
            @JoinColumn(name = "device_id"),
            @JoinColumn(name = "folder_id")
        }
    )
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    private ZonedDateTime sharedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Embeddable
    public static class FolderShareId implements Serializable {
        private Long deviceId;
        private Long folderId;
    }
}
