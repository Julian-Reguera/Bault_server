package baultServer.repositorys;

import org.springframework.data.jpa.repository.JpaRepository;

import baultServer.model.User;

public interface UserInterface extends JpaRepository<User, Long> {

}
