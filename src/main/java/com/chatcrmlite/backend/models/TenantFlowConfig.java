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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getTenant() { return tenant; }
    public void setTenant(User tenant) { this.tenant = tenant; }
    public ConversationState.FlowType getFlowType() { return flowType; }
    public void setFlowType(ConversationState.FlowType flowType) { this.flowType = flowType; }
    public String getConfigurationJson() { return configurationJson; }
    public void setConfigurationJson(String configurationJson) { this.configurationJson = configurationJson; }
    public String getIntentDescription() { return intentDescription; }
    public void setIntentDescription(String intentDescription) { this.intentDescription = intentDescription; }
    public java.util.List<String> getTriggerExamples() { return triggerExamples; }
    public void setTriggerExamples(java.util.List<String> triggerExamples) { this.triggerExamples = triggerExamples; }
    public Integer getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(Integer templateVersion) { this.templateVersion = templateVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static TenantFlowConfigBuilder builder() {
        return new TenantFlowConfigBuilder();
    }

    public static class TenantFlowConfigBuilder {
        private Long id;
        private User tenant;
        private ConversationState.FlowType flowType;
        private String configurationJson;
        private String intentDescription;
        private java.util.List<String> triggerExamples = new java.util.ArrayList<>();
        private Integer templateVersion = 1;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();

        public TenantFlowConfigBuilder id(Long id) { this.id = id; return this; }
        public TenantFlowConfigBuilder tenant(User tenant) { this.tenant = tenant; return this; }
        public TenantFlowConfigBuilder flowType(ConversationState.FlowType flowType) { this.flowType = flowType; return this; }
        public TenantFlowConfigBuilder configurationJson(String configurationJson) { this.configurationJson = configurationJson; return this; }
        public TenantFlowConfigBuilder intentDescription(String intentDescription) { this.intentDescription = intentDescription; return this; }
        public TenantFlowConfigBuilder triggerExamples(java.util.List<String> triggerExamples) { this.triggerExamples = triggerExamples; return this; }
        public TenantFlowConfigBuilder templateVersion(Integer templateVersion) { this.templateVersion = templateVersion; return this; }
        public TenantFlowConfigBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TenantFlowConfigBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public TenantFlowConfig build() {
            TenantFlowConfig c = new TenantFlowConfig(id, tenant, flowType, configurationJson, intentDescription, triggerExamples, templateVersion, createdAt, updatedAt);
            return c;
        }
    }
}
