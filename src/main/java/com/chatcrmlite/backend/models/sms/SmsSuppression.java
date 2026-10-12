package com.chatcrmlite.backend.models.sms;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sms_suppressions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_sms_suppression", columnNames = {"business_id", "phone_number"})
})
@Data
@EqualsAndHashCode(callSuper = true)
public class SmsSuppression extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", length = 50)
    private String businessId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "phone_number", length = 30, nullable = false)
    private String phoneNumber;

    @Column(length = 100)
    private String reason = "USER_OPT_OUT"; // USER_OPT_OUT, CARRIER_BLOCK, INVALID_NUMBER, COMPLAINT

    @Column(length = 50)
    private String source = "INBOUND_STOP"; // INBOUND_STOP, MANUAL_ADMIN, CARRIER_DLR

    @CreationTimestamp
    @Column(name = "suppressed_at", updatable = false)
    private LocalDateTime suppressedAt;

    @PrePersist
    @PreUpdate
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (this.businessId == null && getTenantId() != null) {
            this.businessId = getTenantId().toString();
        }
    }
}
