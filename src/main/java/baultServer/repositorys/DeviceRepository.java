package baultServer.repositorys;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import baultServer.model.Device;
import baultServer.model.User;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByIdAndUser(Long id, User user);

    List<Device> findByUser(User user);
}
