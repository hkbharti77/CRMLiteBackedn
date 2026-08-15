package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.ContactDTO;
import com.chatcrmlite.backend.dto.MessageDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ContactService {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private TagService tagService;

    private boolean isAdmin(User user) {
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.OWNER || user.getRole() == User.Role.AGENT);
    }

    /**
     * Get all contacts for a user as DTOs.
     * Eagerly loads tags to prevent LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public List<ContactDTO> getContactsByUser(User user) {
        List<Contact> contacts;
        if (user.getTenant() != null) {
            contacts = contactRepository.findAllByTenant(user.getTenant());
        } else {
            contacts = contactRepository.findAllByOwnerWithTags(user);
        }
        return contacts.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Get a single contact by ID as DTO.
     * Eagerly loads tags to prevent LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public ContactDTO getContactById(UUID contactId, User owner) {
        Contact contact = contactRepository.findByIdWithTags(contactId)
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        return toDTO(contact);
    }

    @Transactional
    public ContactDTO createContact(com.chatcrmlite.backend.dto.ContactCreateRequestDTO request, User owner) {
        // 1. Normalize phone
        com.chatcrmlite.backend.utils.PhoneUtils phoneUtils = new com.chatcrmlite.backend.utils.PhoneUtils();
        // Assume default region as US or IN. In a real scenario, this could be tenant-configured.
        String normalizedWaId = phoneUtils.normalizeToWaId(request.getWaId(), "US");

        // 2. Application-level duplicate check scoped to tenant
        if (contactRepository.existsByWaIdAndTenant_Id(normalizedWaId, owner.getTenant().getId())) {
            throw new com.chatcrmlite.backend.exceptions.DuplicateContactException("A contact with this WhatsApp number already exists.");
        }

        // 3. Resolve tags strictly within tenant
        List<com.chatcrmlite.backend.models.Tag> resolvedTags = new java.util.ArrayList<>();
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            resolvedTags = tagService.getOrCreateTags(request.getTags(), com.chatcrmlite.backend.models.Tag.TYPE_CONTACT, owner);
        }

        // 4. Create and save Contact
        Contact contact = Contact.builder()
                .name(request.getName() != null && !request.getName().trim().isEmpty() ? request.getName().trim() : null)
                .email(request.getEmail() != null && !request.getEmail().trim().isEmpty() ? request.getEmail().trim() : null)
                .waId(normalizedWaId)
                .source("MANUAL")
                .owner(owner)
                .tags(resolvedTags)
                .botPaused(false)
                .build();
        
        // Tenant is automatically populated via BaseTenantEntity listener but we set owner
        Contact savedContact = contactRepository.save(contact);
        
        return toDTO(savedContact);
    }

    public List<MessageDTO> getChatMessages(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        return messageRepository.findAllByContactOrderByTimestampAsc(contact).stream()
                .map(msg -> MessageDTO.builder()
                        .id(msg.getId())
                        .content(msg.getContent())
                        .direction(msg.getDirection().toString())
                        .timestamp(msg.getTimestamp())
                        .waMessageId(msg.getWaMessageId())
                        .build())
                .collect(Collectors.toList());
    }

    public Contact saveContact(Contact contact) {
        return contactRepository.save(contact);
    }

    @Transactional
    public void updateTags(UUID contactId, List<String> tagNames, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        List<com.chatcrmlite.backend.models.Tag> resolvedTags = 
                tagService.getOrCreateTags(tagNames, com.chatcrmlite.backend.models.Tag.TYPE_CONTACT, owner);
        
        contact.setTags(resolvedTags);
        contactRepository.save(contact);
    }
    
    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService whatsappOutboundService;

    @Autowired
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;

    @Transactional
    public void toggleBotPaused(UUID contactId, boolean botPaused, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        boolean wasPaused = contact.isBotPaused();
        contact.setBotPaused(botPaused);
        contactRepository.save(contact);

        // Send a WhatsApp notification message when switching to Human Mode (botPaused transitions to true)
        if (botPaused && !wasPaused) {
            try {
                String agentName = owner.getDisplayName() != null && !owner.getDisplayName().isBlank()
                        ? owner.getDisplayName()
                        : owner.getEmail();
                String role = owner.getRole() != null ? owner.getRole().name() : "Representative";
                String companyName = owner.getBusinessName() != null && !owner.getBusinessName().isBlank()
                        ? owner.getBusinessName()
                        : "our team";

                String introMessage = String.format(
                        "Hello! 👋 You have been connected with %s (%s) from %s. I will be assisting you personally now.",
                        agentName, role, companyName
                );

                whatsappConfigRepository.findByTenantId(owner.getTenant().getId()).ifPresent(config -> {
                    whatsappOutboundService.sendText(contact, introMessage, config, owner);
                });
            } catch (Exception e) {
                // Log and swallow error so bot toggling succeeds even if notification dispatch fails
                org.slf4j.LoggerFactory.getLogger(ContactService.class)
                        .error("Failed to send human takeover WhatsApp notification to contact {}", contactId, e);
            }
        }
    }

    @Transactional
    public void deleteContact(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getTenant().getId().equals(owner.getTenant().getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        contactRepository.delete(contact);
    }
    
    @Transactional
    public com.chatcrmlite.backend.dto.ImportResultDTO importContacts(com.chatcrmlite.backend.dto.ContactImportBatchRequestDTO request, User owner) {
        com.chatcrmlite.backend.dto.ImportResultDTO result = new com.chatcrmlite.backend.dto.ImportResultDTO();
        com.chatcrmlite.backend.utils.PhoneUtils phoneUtils = new com.chatcrmlite.backend.utils.PhoneUtils();

        if (request.getContacts() == null || request.getContacts().isEmpty()) {
            return result;
        }

        for (com.chatcrmlite.backend.dto.ContactImportRowDTO row : request.getContacts()) {
            try {
                if (row.getWaId() == null || row.getWaId().trim().isEmpty()) {
                    result.addError(new com.chatcrmlite.backend.dto.ImportErrorDTO(row.getFile(), row.getRow(), "whatsapp_number", "MISSING_PHONE", "WhatsApp number is required"));
                    result.incrementFailed();
                    continue;
                }

                String normalizedWaId;
                try {
                    normalizedWaId = phoneUtils.normalizeToWaId(row.getWaId(), "US");
                } catch (Exception e) {
                    result.addError(new com.chatcrmlite.backend.dto.ImportErrorDTO(row.getFile(), row.getRow(), "whatsapp_number", "INVALID_PHONE", "Invalid WhatsApp number format"));
                    result.incrementFailed();
                    continue;
                }

                java.util.Optional<Contact> existingOpt = contactRepository.findByWaIdAndTenant_Id(normalizedWaId, owner.getTenant().getId());
                
                List<com.chatcrmlite.backend.models.Tag> resolvedTags = new java.util.ArrayList<>();
                if (row.getTags() != null && !row.getTags().isEmpty()) {
                    resolvedTags = tagService.getOrCreateTags(row.getTags(), com.chatcrmlite.backend.models.Tag.TYPE_CONTACT, owner);
                }

                if (existingOpt.isPresent()) {
                    Contact existing = existingOpt.get();
                    boolean updated = false;

                    if (row.getName() != null && !row.getName().trim().isEmpty() && (existing.getName() == null || existing.getName().isEmpty())) {
                        existing.setName(row.getName().trim());
                        updated = true;
                    }
                    if (row.getEmail() != null && !row.getEmail().trim().isEmpty() && (existing.getEmail() == null || existing.getEmail().isEmpty())) {
                        existing.setEmail(row.getEmail().trim());
                        updated = true;
                    }
                    if (!resolvedTags.isEmpty()) {
                        for (com.chatcrmlite.backend.models.Tag tag : resolvedTags) {
                            if (!existing.getTags().contains(tag)) {
                                existing.getTags().add(tag);
                                updated = true;
                            }
                        }
                    }

                    if (updated) {
                        contactRepository.save(existing);
                        result.incrementUpdated();
                    } else {
                        result.incrementSkipped();
                    }
                } else {
                    Contact newContact = Contact.builder()
                            .name(row.getName() != null && !row.getName().trim().isEmpty() ? row.getName().trim() : null)
                            .email(row.getEmail() != null && !row.getEmail().trim().isEmpty() ? row.getEmail().trim() : null)
                            .waId(normalizedWaId)
                            .source("CSV")
                            .owner(owner)
                            .tags(resolvedTags)
                            .botPaused(false)
                            .build();
                    contactRepository.save(newContact);
                    result.incrementCreated();
                }
            } catch (Exception e) {
                result.addError(new com.chatcrmlite.backend.dto.ImportErrorDTO(row.getFile(), row.getRow(), "general", "SYSTEM_ERROR", e.getMessage()));
                result.incrementFailed();
            }
        }

        return result;
    }

    @Transactional(readOnly = true)
    public String exportContacts(String search, String source, String botStatus, User owner) {
        List<Contact> contacts;
        if (isAdmin(owner)) {
            contacts = contactRepository.findAllWithTags();
        } else {
            contacts = contactRepository.findAllByOwnerWithTags(owner);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("name,whatsapp_number,email,tags,source,bot_status\n");

        for (Contact c : contacts) {
            // Apply Filters (matching frontend)
            if (source != null && !source.isEmpty() && !source.equals("ALL")) {
                String s = c.getSource() != null ? c.getSource().toUpperCase() : "MANUAL";
                if (!s.equals(source.toUpperCase())) continue;
            }
            if (botStatus != null && !botStatus.isEmpty() && !botStatus.equals("ALL")) {
                boolean isPaused = botStatus.equals("PAUSED");
                if (c.isBotPaused() != isPaused) continue;
            }
            if (search != null && !search.trim().isEmpty()) {
                String q = search.toLowerCase();
                boolean matches = (c.getName() != null && c.getName().toLowerCase().contains(q)) ||
                                  (c.getEmail() != null && c.getEmail().toLowerCase().contains(q)) ||
                                  (c.getWaId() != null && c.getWaId().toLowerCase().contains(q));
                if (!matches) continue;
            }

            String tags = c.getTags().stream().map(Tag::getName).collect(Collectors.joining(","));
            
            csv.append(escapeCsv(c.getName())).append(",")
               .append(escapeCsv(c.getWaId())).append(",")
               .append(escapeCsv(c.getEmail())).append(",")
               .append(escapeCsv(tags)).append(",")
               .append(escapeCsv(c.getSource())).append(",")
               .append(c.isBotPaused() ? "PAUSED" : "ACTIVE").append("\n");
        }

        return csv.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Convert Contact entity to DTO.
     * Must be called within a transaction where tags are already loaded.
     */
    private ContactDTO toDTO(Contact c) {
        return ContactDTO.builder()
                .id(c.getId())
                .waId(c.getWaId())
                .name(c.getName())
                .email(c.getEmail())
                .source(c.getSource())
                .botPaused(c.isBotPaused())
                .tags(c.getTags().stream()
                        .map(Tag::getName)
                        .collect(Collectors.toList()))
                .build();
    }
}
