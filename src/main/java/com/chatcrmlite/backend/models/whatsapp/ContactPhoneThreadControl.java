package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.Contact;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_phone_thread_controls", uniqueConstraints = {
    @UniqueConstraint(name = "uq_contact_phone_thread", columnNames = {"tenant_id", "contact_id", "phone_number_id"})
}, indexes = {
    @Index(name = "idx_thread_ctrl_lookup", columnList = "tenant_id, contact_id, phone_number_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ContactPhoneThreadControl extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @Column(name = "phone_number_id", nullable = false, length = 100)
    private String phoneNumberId;

    @Column(name = "owner_app_id", length = 100)
    private String ownerAppId;

    @Column(name = "previous_owner_app_id", length = 100)
    private String previousOwnerAppId;

    @Column(name = "thread_control_event", length = 50)
    private String threadControlEvent;

    @Builder.Default
    @Column(name = "thread_control_changed_at", nullable = false)
    private Instant threadControlChangedAt = Instant.now();

    @Column(name = "thread_control_source", length = 100)
    private String threadControlSource;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (threadControlChangedAt == null) {
            threadControlChangedAt = Instant.now();
        }
    }
}
