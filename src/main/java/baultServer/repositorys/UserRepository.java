package baultServer.repositorys;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import baultServer.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);

    boolean existsById(Long id);

    boolean existsByEmail(String email);
}
