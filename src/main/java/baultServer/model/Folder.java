package baultServer.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Folder {
    @Id
    private Long id;
    
    private String path;
    private boolean enabled;
}
