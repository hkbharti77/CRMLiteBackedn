package com.chatcrmlite.backend.services.chat;

import com.chatcrmlite.backend.dto.chat.CannedResponseDto;
import com.chatcrmlite.backend.dto.chat.CreateCannedResponseRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.chat.CannedResponse;
import com.chatcrmlite.backend.repositories.chat.CannedResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CannedResponseService {

    private final CannedResponseRepository repository;

    private String cleanShortcut(String shortcut) {
        if (shortcut == null) return "";
        String clean = shortcut.trim().toLowerCase();
        if (clean.startsWith("/")) {
            clean = clean.substring(1).trim();
        }
        return clean;
    }

    @Transactional(readOnly = true)
    public List<CannedResponseDto> getAll(UUID tenantId) {
        if (tenantId == null) return List.of();
        return repository.findByTenantIdOrderByShortcutAsc(tenantId).stream()
                .map(CannedResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CannedResponseDto> getByCategory(UUID tenantId, String category) {
        if (tenantId == null) return List.of();
        if (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category)) {
            return getAll(tenantId);
        }
        return repository.findByTenantIdAndCategoryIgnoreCaseOrderByShortcutAsc(tenantId, category).stream()
                .map(CannedResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CannedResponseDto> search(UUID tenantId, String query) {
        if (tenantId == null) return List.of();
        if (query == null || query.isBlank()) {
            return getAll(tenantId);
        }
        String clean = cleanShortcut(query);
        return repository.searchByKeyword(tenantId, clean).stream()
                .map(CannedResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CannedResponseDto getByShortcut(UUID tenantId, String shortcut) {
        if (tenantId == null || shortcut == null) return null;
        String clean = cleanShortcut(shortcut);
        return repository.findByTenantIdAndShortcutIgnoreCase(tenantId, clean)
                .map(CannedResponseDto::fromEntity)
                .orElse(null);
    }

    @Transactional
    public CannedResponseDto create(CreateCannedResponseRequest req, User user) {
        if (user == null || user.getTenant() == null) {
            throw new IllegalArgumentException("User must belong to a tenant to create canned responses");
        }
        Tenant tenant = user.getTenant();
        String cleanShortcut = cleanShortcut(req.getShortcut());
        if (cleanShortcut.isEmpty()) {
            throw new IllegalArgumentException("Shortcut cannot be empty");
        }

        if (repository.existsByTenantIdAndShortcutIgnoreCase(tenant.getId(), cleanShortcut)) {
            throw new IllegalArgumentException("A canned response with shortcut '/" + cleanShortcut + "' already exists for this tenant");
        }

        CannedResponse entity = new CannedResponse(
                null,
                tenant,
                req.getTitle().trim(),
                cleanShortcut,
                req.getContent().trim(),
                req.getCategory() != null && !req.getCategory().isBlank() ? req.getCategory().trim().toUpperCase() : "GENERAL",
                req.isShared(),
                0L,
                user
        );

        CannedResponse saved = repository.save(entity);
        log.info("✅ [CannedResponse] Created shortcut '/{}' for tenant {}", cleanShortcut, tenant.getId());
        return CannedResponseDto.fromEntity(saved);
    }

    @Transactional
    public CannedResponseDto update(UUID id, CreateCannedResponseRequest req, User user) {
        if (user == null || user.getTenant() == null) {
            throw new IllegalArgumentException("User must belong to a tenant");
        }
        UUID tenantId = user.getTenant().getId();
        CannedResponse entity = repository.findById(id)
                .filter(c -> c.getTenantId() != null && c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Canned response not found or access denied"));

        String cleanShortcut = cleanShortcut(req.getShortcut());
        if (cleanShortcut.isEmpty()) {
            throw new IllegalArgumentException("Shortcut cannot be empty");
        }

        if (repository.existsByTenantIdAndShortcutIgnoreCaseAndIdNot(tenantId, cleanShortcut, id)) {
            throw new IllegalArgumentException("A canned response with shortcut '/" + cleanShortcut + "' already exists for this tenant");
        }

        entity.setTitle(req.getTitle().trim());
        entity.setShortcut(cleanShortcut);
        entity.setContent(req.getContent().trim());
        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            entity.setCategory(req.getCategory().trim().toUpperCase());
        }
        entity.setShared(req.isShared());

        CannedResponse saved = repository.save(entity);
        log.info("✅ [CannedResponse] Updated shortcut '/{}' for tenant {}", cleanShortcut, tenantId);
        return CannedResponseDto.fromEntity(saved);
    }

    @Transactional
    public void delete(UUID id, User user) {
        if (user == null || user.getTenant() == null) {
            throw new IllegalArgumentException("User must belong to a tenant");
        }
        UUID tenantId = user.getTenant().getId();
        CannedResponse entity = repository.findById(id)
                .filter(c -> c.getTenantId() != null && c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Canned response not found or access denied"));

        repository.delete(entity);
        log.info("🗑️ [CannedResponse] Deleted canned response {} (shortcut '/{}') for tenant {}", id, entity.getShortcut(), tenantId);
    }

    @Transactional
    public void recordUsage(UUID id, UUID tenantId) {
        if (id == null || tenantId == null) return;
        repository.findById(id)
                .filter(c -> c.getTenantId() != null && c.getTenantId().equals(tenantId))
                .ifPresent(entity -> {
                    long count = entity.getUsageCount() != null ? entity.getUsageCount() : 0L;
                    entity.setUsageCount(count + 1);
                    repository.save(entity);
                });
    }

    /**
     * Interpolates contact, agent, and tenant metadata variables into canned response templates.
     */
    public String interpolatePlaceholders(String content, Contact contact, User agent) {
        if (content == null || content.isEmpty()) return "";

        String result = content;

        // Contact placeholders
        String contactName = (contact != null && contact.getName() != null && !contact.getName().isBlank())
                ? contact.getName()
                : "Customer";
        String contactPhone = (contact != null && contact.getWaId() != null) ? contact.getWaId() : "";

        result = result.replace("{{contact.name}}", contactName)
                       .replace("{{contact_name}}", contactName)
                       .replace("{{contact.phone}}", contactPhone)
                       .replace("{{contact_phone}}", contactPhone);

        // Agent placeholders
        String agentName = "Our Team";
        if (agent != null) {
            if (agent.getName() != null && !agent.getName().isBlank()) {
                agentName = agent.getName();
            } else if (agent.getDisplayName() != null && !agent.getDisplayName().isBlank()) {
                agentName = agent.getDisplayName();
            }
        }
        result = result.replace("{{agent.name}}", agentName)
                       .replace("{{agent_name}}", agentName);

        // Tenant / Company placeholders
        String companyName = "Our Company";
        if (agent != null && agent.getTenant() != null) {
            Tenant t = agent.getTenant();
            if (t.getBusinessName() != null && !t.getBusinessName().isBlank()) {
                companyName = t.getBusinessName();
            } else if (t.getName() != null && !t.getName().isBlank()) {
                companyName = t.getName();
            }
        } else if (contact != null && contact.getTenant() != null) {
            Tenant t = contact.getTenant();
            if (t.getBusinessName() != null && !t.getBusinessName().isBlank()) {
                companyName = t.getBusinessName();
            }
        }
        result = result.replace("{{company.name}}", companyName)
                       .replace("{{company_name}}", companyName);

        // Date placeholder
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy"));
        result = result.replace("{{date}}", today)
                       .replace("{{today}}", today);

        return result;
    }
}
