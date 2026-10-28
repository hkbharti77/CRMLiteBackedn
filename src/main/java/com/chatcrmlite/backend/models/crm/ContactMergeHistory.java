package com.chatcrmlite.backend.models.crm;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.Contact;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_merge_history", indexes = {
    @Index(name = "idx_contact_merge_tenant", columnList = "tenant_id"),
    @Index(name = "idx_contact_merge_primary", columnList = "primary_contact_id"),
    @Index(name = "idx_contact_merge_secondary", columnList = "secondary_contact_id"),
    @Index(name = "idx_contact_merge_merged_at", columnList = "merged_at DESC")
})
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ContactMergeHistory extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_contact_id", nullable = false)
    private Contact primaryContact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secondary_contact_id", nullable = false)
    private Contact secondaryContact;

    @Column(name = "merged_by_user_id")
    private UUID mergedByUserId;

    @Column(name = "merged_by_user_email", nullable = false)
    private String mergedByUserEmail;

    @Column(name = "pre_merge_snapshot_json", columnDefinition = "TEXT", nullable = false)
    private String preMergeSnapshotJson;

    @Column(name = "fields_resolved_json", columnDefinition = "TEXT", nullable = false)
    private String fieldsResolvedJson;

    @Column(name = "transferred_entities_json", columnDefinition = "TEXT", nullable = false)
    private String transferredEntitiesJson;

    @Column(name = "audit_note", columnDefinition = "TEXT")
    private String auditNote;

    @Builder.Default
    @Column(name = "merged_at", nullable = false)
    private Instant mergedAt = Instant.now();
}
