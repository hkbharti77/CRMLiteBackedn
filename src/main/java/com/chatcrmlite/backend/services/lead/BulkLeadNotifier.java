package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.services.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends post-import email notifications to contacts of newly created leads.
 *
 * This service is called synchronously from the controller after a successful bulk upload.
 * Email failures are captured as {@link RowErrorDTO} entries (rowNumber=0) rather than
 * rolling back the import, so the bulk upload result is always returned to the caller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkLeadNotifier {

    private final EmailService emailService;

    /**
     * Sends a "lead created" notification email to each imported lead's contact,
     * where the contact has a non-blank email address.
     *
     * <p>Any email delivery failure is appended to {@code errors} with
     * {@code rowNumber=0} (row unknown at notification time) and does not abort
     * processing of remaining leads.</p>
     *
     * @param importedLeads leads that were persisted during the bulk upload
     * @param errors        mutable list of row errors; email failures are appended here
     */
    public void notify(List<Lead> importedLeads, List<RowErrorDTO> errors) {
        for (Lead lead : importedLeads) {
            Contact contact = lead.getContact();

            // Skip leads with no contact or no email address
            if (contact == null || contact.getEmail() == null || contact.getEmail().isBlank()) {
                continue;
            }

            String ownerBusinessName = "Our Business";
            if (lead.getOwner() != null
                    && lead.getOwner().getBusinessName() != null
                    && !lead.getOwner().getBusinessName().isBlank()) {
                ownerBusinessName = lead.getOwner().getBusinessName();
            }

            try {
                emailService.sendLeadCreatedToContact(
                        contact.getEmail(),
                        contact.getName(),
                        ownerBusinessName,
                        "You have been added to our CRM."
                );
                log.debug("[BulkLeadNotifier] Notification sent to contact email={}", contact.getEmail());
            } catch (Exception e) {
                log.warn("[BulkLeadNotifier] Failed to send notification to contact email={}. Reason: {}",
                        contact.getEmail(), e.getMessage());
                errors.add(RowErrorDTO.builder()
                        .rowNumber(0)
                        .reason("EMAIL_SEND_FAILED: " + e.getMessage())
                        .build());
            }
        }
    }
}
