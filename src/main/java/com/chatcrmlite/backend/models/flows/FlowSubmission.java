package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.Contact;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "flow_submissions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_submission_tenant_event", columnNames = {"tenant_id", "event_id"})
}, indexes = {
    @Index(name = "idx_submission_tenant", columnList = "tenant_id"),
    @Index(name = "idx_submission_flow", columnList = "flow_id"),
    @Index(name = "idx_submission_event", columnList = "event_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class FlowSubmission extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private String eventId; // wa_message_id

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id")
    @JsonIgnoreProperties({"revisions", "publishedRevision"})
    private WhatsAppFlow flow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revision_id")
    @JsonIgnoreProperties({"flow"})
    private FlowRevision revision;

    @Column(name = "meta_flow_id")
    private String metaFlowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    @JsonIgnoreProperties({"tags", "owner", "assignedAgent"})
    private Contact contact;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "raw_response_json", nullable = false, columnDefinition = "TEXT")
    private String rawResponseJson;

    @Column(name = "normalized_data_json", columnDefinition = "TEXT")
    private String normalizedDataJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    @Builder.Default
    private SubmissionProcessingStatus processingStatus = SubmissionProcessingStatus.RECEIVED;

    @Column(name = "processing_error", length = 2000)
    private String processingError;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
