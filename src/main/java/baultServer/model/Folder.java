package baultServer.model;

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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_folder_device", columnList = "device_id")
})
public class Folder implements Transferable<Folder.Transfer> {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    private String path;
    private boolean enabled;

    /**
     * Nivel de comparticion frente a otros devices del mismo usuario:
     * - NONE: solo el device propietario la ve/edita.
     * - READ: otros devices del user pueden leerla (descargar de ella).
     * - READ_WRITE: otros devices del user pueden leer y escribir (subirle archivos).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sharing sharing = Sharing.NONE;

    public enum Sharing {
        NONE, READ, READ_WRITE;

        /** true si este nivel concede al menos los permisos de {@code required}. */
        public boolean allows(Sharing required) {
            return required != null && this.ordinal() >= required.ordinal();
        }
    }

    // clave de cifrado de la carpeta, cifrada con la master key del servidor
    private byte[] wrappedDek;
    // Clave necesaria para cifrar la key de la carpeta (junto con la master key permite recuperar la key)
    private byte[] dekWrapIv;
    // Permite cambiar el algoritmo de cifrado
    private String encryptionAlgorithm;
    // Version de la key del servidor que descifra la key de la base de datos (por si se cambia)
    private Integer keyVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Override
    public Transfer toTransfer() {
        return new Transfer(id, path, enabled, sharing == null ? Sharing.NONE : sharing,
                wrappedDek != null, device.getId());
    }

    @Getter
    @AllArgsConstructor
    public static class Transfer {
        private Long id;
        private String path;
        private boolean enabled;
        private Sharing sharing;
        private boolean encrypted;
        private Long deviceId;
    }
}
