package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "contact_channel_preferences")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactChannelPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Builder.Default
    @Column(name = "email_consent_status", length = 20)
    private String emailConsentStatus = "UNKNOWN";

    @Builder.Default
    @Column(name = "whatsapp_consent_status", length = 20)
    private String whatsappConsentStatus = "UNKNOWN";

    @Builder.Default
    @Column(name = "sms_consent_status", length = 20)
    private String smsConsentStatus = "UNKNOWN";

    @Builder.Default
    @Column(name = "is_globally_suppressed")
    private Boolean isGloballySuppressed = false;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
