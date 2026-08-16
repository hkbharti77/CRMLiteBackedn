package com.chatcrmlite.backend.services.bootstrap;

import com.chatcrmlite.backend.models.flows.FlowSubmission;
import com.chatcrmlite.backend.models.flows.SubmissionProcessingStatus;
import com.chatcrmlite.backend.repositories.flows.FlowSubmissionRepository;
import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.services.whatsapp.flows.FlowSubmissionProcessor;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Component
public class TenantDataRepairRunner implements ApplicationRunner {

    private final EntityManager entityManager;
    private final FlowSubmissionRepository flowSubmissionRepository;
    private final FlowSubmissionProcessor flowSubmissionProcessor;
    private final TransactionTemplate transactionTemplate;

    public TenantDataRepairRunner(EntityManager entityManager,
                                  FlowSubmissionRepository flowSubmissionRepository,
                                  FlowSubmissionProcessor flowSubmissionProcessor,
                                  PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.flowSubmissionRepository = flowSubmissionRepository;
        this.flowSubmissionProcessor = flowSubmissionProcessor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantContext.setAdminMode(true);
        try {
            log.info("🛠️ [DataRepair] Running tenant and lead integrity check on startup...");

            // 1. Backfill tenant_id for all orphaned records
            executeSqlSafely("UPDATE contacts SET tenant_id = (SELECT id FROM tenants ORDER BY created_at ASC LIMIT 1) WHERE tenant_id IS NULL", "contacts.tenant_id");
            executeSqlSafely("UPDATE leads SET tenant_id = (SELECT id FROM tenants ORDER BY created_at ASC LIMIT 1) WHERE tenant_id IS NULL", "leads.tenant_id");
            executeSqlSafely("UPDATE flow_submissions SET tenant_id = (SELECT id FROM tenants ORDER BY created_at ASC LIMIT 1) WHERE tenant_id IS NULL", "flow_submissions.tenant_id");
            executeSqlSafely("UPDATE flow_outbox_events SET tenant_id = (SELECT id FROM tenants ORDER BY created_at ASC LIMIT 1) WHERE tenant_id IS NULL", "flow_outbox_events.tenant_id");
            executeSqlSafely("UPDATE chat_messages SET tenant_id = (SELECT id FROM tenants ORDER BY created_at ASC LIMIT 1) WHERE tenant_id IS NULL", "chat_messages.tenant_id");

            // 2. Backfill owner_id for any orphaned leads and contacts
            executeSqlSafely("UPDATE contacts SET owner_id = (SELECT id FROM app_users WHERE role IN ('OWNER', 'ADMIN') ORDER BY id ASC LIMIT 1) WHERE owner_id IS NULL", "contacts.owner_id");
            executeSqlSafely("UPDATE leads SET owner_id = (SELECT id FROM app_users WHERE role IN ('OWNER', 'ADMIN') ORDER BY id ASC LIMIT 1) WHERE owner_id IS NULL", "leads.owner_id");

            // 3. Reprocess any unprocessed / pending Flow Submissions
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    List<FlowSubmission> pendingSubmissions = flowSubmissionRepository.findAll().stream()
                            .filter(s -> s.getProcessingStatus() == null || s.getProcessingStatus() == SubmissionProcessingStatus.RECEIVED || s.getProcessingStatus() == SubmissionProcessingStatus.PROCESSING_FAILED)
                            .toList();

                    if (!pendingSubmissions.isEmpty()) {
                        log.info("🔄 [DataRepair] Found {} un-processed FlowSubmissions. Processing them now into Leads...", pendingSubmissions.size());
                        for (FlowSubmission sub : pendingSubmissions) {
                            try {
                                flowSubmissionProcessor.processSubmission(sub);
                                log.info("✅ [DataRepair] Reprocessed FlowSubmission {} into Lead successfully.", sub.getId());
                            } catch (Exception subEx) {
                                log.warn("⚠️ [DataRepair] Failed to reprocess FlowSubmission {}: {}", sub.getId(), subEx.getMessage());
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                log.warn("⚠️ [DataRepair] Error re-processing pending submissions: {}", ex.getMessage());
            }

            log.info("✅ [DataRepair] Startup integrity check completed successfully.");

        } finally {
            TenantContext.clear();
        }
    }

    private void executeSqlSafely(String sql, String label) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int count = entityManager.createNativeQuery(sql).executeUpdate();
                log.info("🛠️ [DataRepair] Updated {} records for {}", count, label);
            });
        } catch (Exception ex) {
            log.warn("⚠️ [DataRepair] Skipped {}: {}", label, ex.getMessage());
        }
    }
}
