package baultServer.model;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import lombok.Data;

@Entity
@Data
public class Folder {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private Long id;

    private String path;
    private boolean enabled;

    // clave de cifrado de la carpeta, cifrada con la master key del servidor
    private byte[] wrappedDek;
    // Clave necesaria para cifrar la key de la carpeta (junto con la master key permite recuperar la key)
    private byte[] dekWrapIv;
    // Permite cambiar el algoritmo de cifrado
    private String encryptionAlgorithm;
    // Version de la key del servidor que descifra la key de la base de datos (por si se cambia)
    private Integer keyVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @OneToMany(mappedBy = "folder")
    private List<FolderShare> sharedWith;
}
