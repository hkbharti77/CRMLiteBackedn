package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Ticket;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Public-facing support form configuration.
 * Returned to the frontend to render the form.
 */
@Data
@Builder
public class SupportFormConfigDTO {

    // ── Form Content ───────────────────────────────────────────────────────

    private String formTitle;
    private String formDescription;
    private String successMessage;

    // ── Field Configuration ────────────────────────────────────────────────

    private boolean phoneRequired;
    private boolean categoryRequired;
    private List<String> categories;

    // ── Branding ───────────────────────────────────────────────────────────

    private String primaryColor;
    private String logoUrl;

    // ── Business Info ──────────────────────────────────────────────────────

    private UUID businessId;
    private String businessName;

    // ── Status ─────────────────────────────────────────────────────────────

    private boolean enabled;
}
