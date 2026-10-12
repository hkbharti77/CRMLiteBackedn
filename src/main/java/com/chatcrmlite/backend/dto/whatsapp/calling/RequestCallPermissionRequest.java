package com.chatcrmlite.backend.dto.whatsapp.calling;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestCallPermissionRequest {

    /**
     * Optional Contact UUID within tenant. If provided, userWaId is resolved automatically.
     */
    private UUID contactId;

    /**
     * Target user's WhatsApp number (e.g. 919876543210).
     */
    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone number must be 7 to 15 digits, optionally prefixed with '+'")
    private String userWaId;

    /**
     * Optional tenant phone number ID. Defaults to tenant's configured WhatsApp number.
     */
    @Size(max = 64, message = "Phone number ID must not exceed 64 characters")
    private String phoneNumberId;

    /**
     * Optional message text to send in the permission prompt.
     */
    @Size(max = 1024, message = "Message text must not exceed 1024 characters")
    private String messageText;
}
