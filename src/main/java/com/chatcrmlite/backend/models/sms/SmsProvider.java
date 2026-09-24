package com.chatcrmlite.backend.models.sms;

import com.chatcrmlite.backend.utils.EncryptionConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "sms_providers")
@Data
public class SmsProvider {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "business_id", length = 50, nullable = false)
    private String businessId;

    @Column(name = "provider_type", length = 50, nullable = false)
    private String providerType; // TWILIO, MSG91, FAST2SMS, AWS_SNS

    @Column(length = 255, nullable = false)
    private String name;

    @Column(name = "sender_id", length = 50, nullable = false)
    private String senderId;

    @Column(name = "credentials_payload", columnDefinition = "TEXT", nullable = false)
    @Convert(converter = EncryptionConverter.class)
    private String credentialsPayload; // JSON string encrypted in DB

    @Column(name = "requests_per_second")
    private Integer requestsPerSecond = 10;

    @Column(name = "is_default")
    private Boolean isDefault = false;

    @Column(length = 50)
    private String status = "UNVERIFIED"; // CONNECTED, ERROR, UNVERIFIED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
