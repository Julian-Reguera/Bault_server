package baultServer.services;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.BillingPlanRepository;
import baultServer.repositorys.DeviceRepository;
import baultServer.repositorys.TransferRepository;

@Service
public class AccountService {

    private static final long BYTES_PER_MB = 1_000_000L;

    private final DeviceRepository deviceRepository;
    private final TransferRepository transferRepository;
    private final BillingPlanRepository planRepository;

    public AccountService(DeviceRepository deviceRepository,
                          TransferRepository transferRepository,
                          BillingPlanRepository planRepository) {
        this.deviceRepository = deviceRepository;
        this.transferRepository = transferRepository;
        this.planRepository = planRepository;
    }

    public AccountDto getAccount(User user) {
        UserDto userDto = UserDto.of(user);
        BillingPlan currentPlan = user.getBillingPlan();
        PlanDto planDto = currentPlan == null ? null : PlanDto.of(currentPlan, true);
        UsageDto usage = computeUsage(user);
        ZonedDateTime nextRenewalAt = computeRenewalDate(user);
        return new AccountDto(userDto, planDto, usage,
                user.getPlanSubscribedAt(), user.getPlanLastPaymentAt(), nextRenewalAt);
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

    private UsageDto computeUsage(User user) {
        long activeDevices = deviceRepository.countByUserAndStatus(user, Device.Status.ACTIVE);
        long trafficBytes = transferRepository.sumSizeBytesSince(user, ZonedDateTime.now().minusMonths(1));
        long trafficMb = trafficBytes / BYTES_PER_MB;
        return new UsageDto(activeDevices, trafficMb, trafficBytes);
    }

    private ZonedDateTime computeRenewalDate(User user) {
        if (user.getPlanLastPaymentAt() != null) return user.getPlanLastPaymentAt().plusMonths(1);
        if (user.getPlanSubscribedAt() != null) return user.getPlanSubscribedAt().plusMonths(1);
        return null;
    }

    // ---------------- DTOs ----------------

    public record UserDto(Long id, String email, String firstName, String lastName, String initials) {
        public static UserDto of(User u) {
            String initials = computeInitials(u.getFirstName(), u.getLastName());
            return new UserDto(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), initials);
        }

        private static String computeInitials(String first, String last) {
            StringBuilder sb = new StringBuilder(2);
            if (first != null && !first.isBlank()) sb.append(Character.toUpperCase(first.charAt(0)));
            if (last != null && !last.isBlank()) sb.append(Character.toUpperCase(last.charAt(0)));
            return sb.length() == 0 ? "" : sb.toString();
        }
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

    public record UsageDto(long activeDevices, long monthlyTrafficUsedMb, long monthlyTrafficUsedBytes) {}

    public record AccountDto(
            UserDto user,
            PlanDto currentPlan,
            UsageDto usage,
            ZonedDateTime subscribedAt,
            ZonedDateTime lastPaymentAt,
            ZonedDateTime nextRenewalAt) {}
}
