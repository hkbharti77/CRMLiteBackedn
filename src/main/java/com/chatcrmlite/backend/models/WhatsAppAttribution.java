package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_attributions", indexes = {
    @Index(name = "idx_wa_attr_tenant_conv", columnList = "tenant_id, conversation_id"),
    @Index(name = "idx_wa_attr_tenant_phone", columnList = "tenant_id, phone_number_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id")
    private UUID conversationId; // Link to the WhatsApp Conversation / WebChatSession

    @Column(name = "waba_id")
    private String wabaId;

    @Column(name = "phone_number_id")
    private String phoneNumberId;

    @Column(name = "ctwa_clid")
    private String ctwaClid;

    @Column(name = "source_type")
    private String sourceType; // AD, ORGANIC, etc.

    @Column(name = "source_id")
    private String sourceId; // e.g. Ad ID

    @Column(name = "captured_at", nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
    }
}
