package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(unique = true, nullable = false)
    private String phoneNumberId;

    private String wabaId;

    @Column(length = 1000)
    private String accessToken;

    private String verifyToken;

    @Column(columnDefinition = "TEXT")
    private String interactiveMenuJson;

    @Column(columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(columnDefinition = "TEXT")
    private String returningMessage;

    @Column(length = 500)
    private String reviewUrl;

    @Column(length = 500)
    private String portfolioUrl;

    @Column(columnDefinition = "TEXT")
    private String offerText;

    @Column(length = 255)
    private String sosNote;

    @Column(length = 50)
    private String thirdButtonType; // TRUST, SOCIAL, OFFER, NONE

    @Column(columnDefinition = "TEXT")
    private String customSubMenusJson;

    @Column(columnDefinition = "TEXT")
    private String customMessagesJson;

    @Builder.Default
    private Boolean showAboutContact = true;

    @Builder.Default
    private Boolean showTrustButton = true;

    @Builder.Default
    private Boolean showOfferButton = true;

    @Builder.Default
    private Boolean showSosButton = true;
}
