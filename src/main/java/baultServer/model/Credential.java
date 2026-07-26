package baultServer.model;

import java.io.Serializable;
import java.time.ZonedDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@IdClass(Credential.CredentialId.class)
public class Credential {
    public enum Provider {
        GOOGLE, FACEBOOK
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CredentialId implements Serializable {
        private String externId;
        private Provider provider;
    }

    @Id
    private String externId;

    @Id
    @Enumerated(EnumType.STRING)
    private Provider provider;

    private ZonedDateTime createdAt;
}
