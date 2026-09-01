package com.chatcrmlite.backend.models.voice;

import com.chatcrmlite.backend.models.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_usages", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"business_id", "usage_date"})
})
public class VoiceUsage implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private User business;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "stt_seconds_total")
    private Integer sttSecondsTotal = 0;

    @Column(name = "tts_characters_total")
    private Integer ttsCharactersTotal = 0;

    @Column(name = "request_count")
    private Integer requestCount = 0;

    @Column(name = "estimated_cost_usd", precision = 10, scale = 4)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.sttSecondsTotal == null) this.sttSecondsTotal = 0;
        if (this.ttsCharactersTotal == null) this.ttsCharactersTotal = 0;
        if (this.requestCount == null) this.requestCount = 0;
        if (this.estimatedCostUsd == null) this.estimatedCostUsd = BigDecimal.ZERO;
    }
}
