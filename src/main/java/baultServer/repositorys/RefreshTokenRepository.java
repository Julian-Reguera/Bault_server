package baultServer.repositorys;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import baultServer.model.Device;
import baultServer.model.RefreshToken;
import baultServer.model.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.user = :user and r.revoked = false")
    int revokeAllByUser(@Param("user") User user);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.device = :device and r.revoked = false")
    int revokeAllByDevice(@Param("device") Device device);
}
