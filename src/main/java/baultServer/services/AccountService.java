package baultServer.services;

import java.time.ZonedDateTime;

import org.springframework.stereotype.Service;

import baultServer.model.BillingPlan;
import baultServer.model.Device;
import baultServer.model.User;
import baultServer.repositorys.DeviceRepository;
import baultServer.repositorys.TransferRepository;
import baultServer.services.PlanService.PlanDto;

@Service
public class AccountService {

    private static final long BYTES_PER_MB = 1_000_000L;

    private final DeviceRepository deviceRepository;
    private final TransferRepository transferRepository;

    public AccountService(DeviceRepository deviceRepository,
                          TransferRepository transferRepository) {
        this.deviceRepository = deviceRepository;
        this.transferRepository = transferRepository;
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

    public record UsageDto(long activeDevices, long monthlyTrafficUsedMb, long monthlyTrafficUsedBytes) {}

    public record AccountDto(
            UserDto user,
            PlanDto currentPlan,
            UsageDto usage,
            ZonedDateTime subscribedAt,
            ZonedDateTime lastPaymentAt,
            ZonedDateTime nextRenewalAt) {}
}
