package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    private String password;

    private String businessName;

    private String businessType;

    private String businessSubType;

    private String address;
    
    @Column(columnDefinition = "TEXT")
    private String aboutUs;

    private Double latitude;

    private Double longitude;

    private String logoUrl;

    @Builder.Default
    private Boolean onboardingCompleted = false;

    private LocalDateTime consentAt;

    private String displayName;

    private String phone;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Builder.Default
    private String accountStatus = "ACTIVE"; // ACTIVE, LOCKED

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_ip_whitelist", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "ip_address")
    private java.util.Set<String> ipWhitelist = new java.util.HashSet<>();

    @Builder.Default
    private Boolean biometricsEnabled = false;

    @Builder.Default
    private Boolean loginAlertsEnabled = false;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private WhatsAppConfig whatsappConfig;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (role == null) {
            role = Role.OWNER;
        }
    }

    public enum Role {
        OWNER, ADMIN, AGENT
    }
}
