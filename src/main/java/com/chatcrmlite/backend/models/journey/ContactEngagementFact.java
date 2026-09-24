package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "contact_engagement_facts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactEngagementFact {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "last_email_opened_at")
    private ZonedDateTime lastEmailOpenedAt;

    @Column(name = "last_email_clicked_at")
    private ZonedDateTime lastEmailClickedAt;

    @Column(name = "last_whatsapp_reply_at")
    private ZonedDateTime lastWhatsappReplyAt;

    @Column(name = "last_sms_delivered_at")
    private ZonedDateTime lastSmsDeliveredAt;

    @Builder.Default
    @Column(name = "message_count")
    private Integer messageCount = 0;

    @Builder.Default
    @Column(name = "reply_count")
    private Integer replyCount = 0;

    @Builder.Default
    @Column(name = "facts_json", columnDefinition = "jsonb")
    private String factsJson = "{}";

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
