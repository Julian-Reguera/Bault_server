package baultServer.services;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.User;
import baultServer.repositorys.FolderRepository;

@Service
public class FolderService {

    private static final String DEFAULT_ALGORITHM = "AES-256-GCM";

    private final FolderRepository repository;
    private final CryptoService cryptoService;
    private final EventWsBroadcaster eventBroadcaster;

    public FolderService(FolderRepository repository,
                         CryptoService cryptoService,
                         EventWsBroadcaster eventBroadcaster) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.eventBroadcaster = eventBroadcaster;
    }

    public List<Folder.Transfer> findSharedByDevice(Device device) {
        return repository.findByDeviceAndSharingNot(device, Folder.Sharing.NONE).stream()
                .map(Folder::toTransfer)
                .toList();
    }

    public List<Folder.Transfer> findSharedByUser(User user) {
        return repository.findByDeviceUserAndSharingNot(user, Folder.Sharing.NONE).stream()
                .map(Folder::toTransfer)
                .toList();
    }

    /** Carpetas compartidas por otros devices del mismo user (excluye las del caller). */
    public List<Folder.Transfer> findSharedWithDevice(User user, Device callerDevice) {
        return repository.findByDeviceUserAndDeviceNotAndSharingNot(user, callerDevice, Folder.Sharing.NONE).stream()
                .map(Folder::toTransfer)
                .toList();
    }

    @Transactional
    public Folder updateSharing(Folder folder, Folder.Sharing sharing) {
        folder.setSharing(sharing == null ? Folder.Sharing.NONE : sharing);
        Folder saved = repository.save(folder);
        eventBroadcaster.folderUpdated(saved.getDevice().getUser().getId(), saved);
        return saved;
    }

    /** Soft-delete: marca la folder como no compartida y deshabilitada, sin borrar la fila. */
    @Transactional
    public Folder unshare(Folder folder) {
        folder.setEnabled(false);
        folder.setSharing(Folder.Sharing.NONE);
        Folder saved = repository.save(folder);
        eventBroadcaster.folderDeleted(saved.getDevice().getUser().getId(), saved.getId());
        return saved;
    }

    @Transactional
    public Folder create(Device device, String path, Folder.Sharing sharing, boolean encrypted) {
        Folder folder = new Folder();
        folder.setDevice(device);
        folder.setPath(path);
        folder.setSharing(sharing == null ? Folder.Sharing.NONE : sharing);
        folder.setEnabled(true);
        folder = repository.save(folder);
        if (encrypted) {
            byte[] dek = cryptoService.newDek();
            try {
                CryptoService.WrappedKey wrapped = cryptoService.wrap(dek, folder.getId());
                folder.setWrappedDek(wrapped.wrapped());
                folder.setDekWrapIv(wrapped.iv());
                folder.setKeyVersion(wrapped.version());
                folder.setEncryptionAlgorithm(DEFAULT_ALGORITHM);
                folder = repository.save(folder);
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        }
        eventBroadcaster.folderCreated(device.getUser().getId(), folder);
        return folder;
    }
}
