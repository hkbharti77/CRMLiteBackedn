package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "drip_sequence_steps", indexes = {
    @Index(name = "idx_dss_seq", columnList = "sequence_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DripSequenceStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sequence_id", nullable = false)
    private DripSequence sequence;

    @Column(nullable = false)
    private Integer stepOrder;

    @Column(nullable = false)
    private Integer delayHours; // e.g., 48 hours after step 1

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_snapshot_id", nullable = false)
    private WhatsAppTemplateSnapshot templateSnapshot;

    @Column(columnDefinition = "TEXT")
    private String variableMappingJson;

    @Builder.Default
    private Boolean exitIfReplied = true;

    @Builder.Default
    private Boolean exitIfStatusChanged = true;
}
