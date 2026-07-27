package baultServer.model;

import jakarta.persistence.Entity;
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
        @Index(name = "idx_folder_device", columnList = "device_id")
})
public class Folder {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    private String path;
    private boolean enabled;

    // Si es true, cualquier device del mismo usuario puede verla y editarla.
    // Si es false, solo el device propietario.
    private boolean shared;

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
}
