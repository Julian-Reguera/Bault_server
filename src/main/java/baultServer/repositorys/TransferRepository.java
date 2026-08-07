package baultServer.repositorys;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import baultServer.model.Device;
import baultServer.model.Transfer;
import baultServer.model.User;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

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
}
