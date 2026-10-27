package com.chatcrmlite.backend.services.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreditReconciliationJob {

    private final CreditMeteringService creditMeteringService;

    @Scheduled(fixedDelayString = "${credit.reconciliation.interval.ms:300000}") // 5 minutes default
    @SchedulerLock(name = "CreditReconciliationJob_run", lockAtLeastFor = "30s", lockAtMostFor = "4m")
    public void runReconciliation() {
        try {
            int reclaimed = creditMeteringService.reconcileStaleReservations();
            if (reclaimed > 0) {
                log.info("🔄 [CreditReconciliationJob] Successfully reconciled {} expired credit reservation(s)", reclaimed);
            }
        } catch (Exception ex) {
            log.error("❌ [CreditReconciliationJob] Error during credit reconciliation: {}", ex.getMessage(), ex);
        }
    }
}
