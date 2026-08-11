package baultServer.repositorys;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import baultServer.model.BillingPlan;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

    List<BillingPlan> findByEnabledTrueOrderByMonthlyPriceAsc();

    Optional<BillingPlan> findByName(String name);
}
