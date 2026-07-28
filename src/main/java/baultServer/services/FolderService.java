package baultServer.services;

import java.util.List;

import org.springframework.stereotype.Service;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.User;
import baultServer.repositorys.FolderRepository;

@Service
public class FolderService {

    private final FolderRepository repository;

    public FolderService(FolderRepository repository) {
        this.repository = repository;
    }

    public List<Folder.Transfer> findSharedByDevice(Device device) {
        return repository.findByDeviceAndSharedTrue(device).stream()
                .map(Folder::toTransfer)
                .toList();
    }

    public List<Folder.Transfer> findSharedByUser(User user) {
        return repository.findByDeviceUserAndSharedTrue(user).stream()
                .map(Folder::toTransfer)
                .toList();
    }
}
