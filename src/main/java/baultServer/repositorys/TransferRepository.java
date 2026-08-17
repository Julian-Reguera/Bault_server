package baultServer.repositorys;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import baultServer.model.Device;
import baultServer.model.Transfer;
import baultServer.model.User;

public interface TransferRepository extends JpaRepository<Transfer, Long>, JpaSpecificationExecutor<Transfer> {

    /**
     * Suma los sizeBytes de todas las transferencias del usuario (como sender o receiver)
     * creadas desde {@code since} hasta ahora. Las PENDING contribuyen con 0.
     */
    @Query("""
           SELECT COALESCE(SUM(t.sizeBytes), 0)
           FROM Transfer t
           WHERE t.createdAt >= :since
             AND (t.sender.user = :user OR t.receiver.user = :user)
           """)
    long sumSizeBytesSince(@Param("user") User user, @Param("since") ZonedDateTime since);

    /** Cuenta cuantas transferencias tiene el usuario en el estado indicado (como owner/iniciador). */
    @Query("""
           SELECT COUNT(t)
           FROM Transfer t
           WHERE t.owner.user = :user AND t.status = :status
           """)
    long countByOwnerUserAndStatus(@Param("user") User user, @Param("status") Transfer.Status status);

    /** Busca una transferencia previa con la misma idempotency-key para el mismo owner (iniciador). */
    Optional<Transfer> findByOwnerAndIdempotencyKey(Device owner, String idempotencyKey);

    /** Transferencias en un estado dado creadas antes de un instante. Para limpieza por TTL. */
    List<Transfer> findByStatusAndCreatedAtBefore(Transfer.Status status, ZonedDateTime before);

    List<Transfer> findByStatus(Transfer.Status status);

    /** Devices distintos que han sido sender en alguna transferencia del usuario. */
    @Query("""
           SELECT DISTINCT t.sender FROM Transfer t
           WHERE t.owner.user = :user AND t.sender IS NOT NULL
           ORDER BY t.sender.alias
           """)
    List<Device> distinctSendersForUser(@Param("user") User user);

    /** Devices distintos que han sido receiver en alguna transferencia del usuario. */
    @Query("""
           SELECT DISTINCT t.receiver FROM Transfer t
           WHERE t.owner.user = :user AND t.receiver IS NOT NULL
           ORDER BY t.receiver.alias
           """)
    List<Device> distinctReceiversForUser(@Param("user") User user);

    /**
     * Transferencias third-party en estado {@code status} en las que {@code device}
     * participa como sender o receiver pero no es el owner. Usado por los peers para
     * descubrir transferencias que un tercer device orquestó para ellos (por si perdieron
     * la notificación WS por estar offline).
     */
    @Query("""
           SELECT t FROM Transfer t
           WHERE t.status = :status
             AND (t.sender.id = :deviceId OR t.receiver.id = :deviceId)
             AND t.owner.id <> :deviceId
             AND t.owner.id <> t.sender.id
             AND t.owner.id <> t.receiver.id
           ORDER BY t.createdAt DESC
           """)
    List<Transfer> findThirdPartyByPeerAndStatus(@Param("deviceId") Long deviceId,
                                                 @Param("status") Transfer.Status status);
}
