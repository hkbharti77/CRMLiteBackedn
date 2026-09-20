package com.chatcrmlite.backend.models;

import com.chatcrmlite.backend.utils.EncryptionConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_providers")
@Data
public class EmailProvider {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "business_id", length = 50, nullable = false)
    private String businessId;

    @Column(name = "provider_type", length = 50, nullable = false)
    private String providerType; // AWS_SES, BREVO, ZOHO, SMTP

    @Column(length = 255, nullable = false)
    private String name;

    @Column(name = "from_email", length = 255, nullable = false)
    private String fromEmail;

    @Column(name = "credentials_payload", columnDefinition = "TEXT", nullable = false)
    @Convert(converter = EncryptionConverter.class)
    private String credentialsPayload; // JSON string encrypted in DB

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
