package baultServer.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import baultServer.model.BillingPlan;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;

@Service
public class PlanService {

    private final BillingPlanRepository planRepository;

    public PlanService(BillingPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public List<PlanDto> listPlans(User user) {
        BillingPlan current = user.getBillingPlan();
        Long currentId = current == null ? null : current.getId();

        List<BillingPlan> plans = new ArrayList<>(planRepository.findByEnabledTrueOrderByMonthlyPriceAsc());
        //Aunque el plan del user este disabled, tiene que aparecer en el catalogo (marcado como current).
        if (current != null && !current.isEnabled()) {
            plans.add(current);
        }
        plans.sort(Comparator.comparingInt(BillingPlan::getMonthlyPrice));
        return plans.stream()
                .map(p -> PlanDto.of(p, currentId != null && currentId.equals(p.getId())))
                .toList();
    }

    public record PlanDto(
            Long id,
            String name,
            int monthlyPriceCents,
            int annualPriceCents,
            int maxSpeedMbps,
            int maxTrafficMb,
            int maxDevices,
            int maxConcurrentTransfers,
            boolean encryptedFoldersIncluded,
            boolean isCurrent) {
        public static PlanDto of(BillingPlan p, boolean isCurrent) {
            return new PlanDto(
                    p.getId(),
                    p.getName(),
                    p.getMonthlyPrice(),
                    p.getAnnualPrice(),
                    p.getMaxSpeed(),
                    p.getMaxTraffic(),
                    p.getMaxDevices(),
                    p.getMaxConcurrentTransfers(),
                    p.isEncryptedFoldersIncluded(),
                    isCurrent);
        }
    }
}
