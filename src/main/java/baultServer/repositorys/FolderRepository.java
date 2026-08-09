package baultServer.repositorys;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import baultServer.model.Device;
import baultServer.model.Folder;
import baultServer.model.User;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    List<Folder> findByDeviceAndSharingNot(Device device, Folder.Sharing sharing);

    List<Folder> findByDeviceUserAndSharingNot(User user, Folder.Sharing sharing);

    List<Folder> findByDeviceUserAndDeviceNotAndSharingNot(User user, Device device, Folder.Sharing sharing);
}
