package baultServer.repositorys;

import java.time.ZonedDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import baultServer.model.EmailCode;
import baultServer.model.User;

public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

    @Query("select c from EmailCode c where c.user = :user and c.purpose = :purpose " +
           "order by c.createdAt desc limit 1")
    Optional<EmailCode> findLatest(@Param("user") User user,
                                   @Param("purpose") EmailCode.Purpose purpose);

    @Query("select c from EmailCode c where c.user = :user and c.purpose = :purpose " +
           "and c.consumedAt is null order by c.createdAt desc limit 1")
    Optional<EmailCode> findLatestActive(@Param("user") User user,
                                         @Param("purpose") EmailCode.Purpose purpose);

    @Modifying
    @Query("delete from EmailCode c where c.expiresAt < :now")
    int deleteExpired(@Param("now") ZonedDateTime now);

    @Modifying
    @Query("update EmailCode c set c.consumedAt = :now " +
           "where c.user = :user and c.purpose = :purpose and c.consumedAt is null")
    int consumeAllActive(@Param("user") User user,
                         @Param("purpose") EmailCode.Purpose purpose,
                         @Param("now") ZonedDateTime now);
}
