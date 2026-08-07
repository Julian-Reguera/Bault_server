package baultServer.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import baultServer.model.Transfer;
import baultServer.repositorys.TransferRepository;

@Service
public class TransferCleanupService {

    private static final Logger log = LogManager.getLogger(TransferCleanupService.class);

    private final TransferRepository transferRepository;

    public TransferCleanupService(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    /**
     * Al arrancar, cualquier transferencia que se quedo en IN_PROGRESS es una huerfana
     * (el servidor cayo mientras se transmitia): la marcamos como FAILED. En operacion
     * normal los propios endpoints de streaming detectan sus errores y llaman markFailed,
     * asi que no hace falta un centinela periodico.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failOrphanedOnStartup() {
        List<Transfer> orphaned = transferRepository.findByStatus(Transfer.Status.IN_PROGRESS);
        for (Transfer t : orphaned) {
            t.setStatus(Transfer.Status.FAILED);
            t.setFailureReason("Server restarted while in progress");
        }
        if (!orphaned.isEmpty()) {
            transferRepository.saveAll(orphaned);
            log.warn("Marked {} orphaned IN_PROGRESS transfers as FAILED on startup", orphaned.size());
        }
    }
}
