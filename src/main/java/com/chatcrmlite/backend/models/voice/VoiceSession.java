package com.chatcrmlite.backend.models.voice;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_sessions")
public class VoiceSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private User business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(name = "visitor_id", nullable = false, length = 100)
    private String visitorId;

    @Column(name = "language", length = 10)
    private String language = "en";

    @Column(name = "voice_id", length = 50)
    private String voiceId = "deepgram/flux-tts:free";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private VoiceSessionStatus status = VoiceSessionStatus.ACTIVE;

    @Column(name = "total_turns")
    private Integer totalTurns = 0;

    @Column(name = "active_turn_number")
    private Integer activeTurnNumber = 0;

    public boolean isCurrentTurn(int turnNumber) {
        return this.activeTurnNumber != null && this.activeTurnNumber == turnNumber;
    }

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<VoiceTurn> turns = new ArrayList<>();

    public enum VoiceSessionStatus {
        ACTIVE, COMPLETED, ABANDONED, ESCALATED
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        if (this.totalTurns == null) {
            this.totalTurns = 0;
        }
    }
}
