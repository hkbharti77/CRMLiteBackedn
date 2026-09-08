package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_flow_configs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "flow_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantFlowConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false)
    private ConversationState.FlowType flowType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_json", nullable = false, columnDefinition = "jsonb")
    private String configurationJson;

    @Column(name = "intent_description", columnDefinition = "TEXT")
    private String intentDescription;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_examples", columnDefinition = "jsonb")
    @Builder.Default
    private java.util.List<String> triggerExamples = new java.util.ArrayList<>();

    @Column(name = "template_version", nullable = false)
    @Builder.Default
    private Integer templateVersion = 1;

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
