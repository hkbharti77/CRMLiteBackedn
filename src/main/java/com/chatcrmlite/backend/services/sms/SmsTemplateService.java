package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.models.sms.SmsTemplate;
import com.chatcrmlite.backend.repositories.SmsTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsTemplateService {

    private final SmsTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final Set<String> ALLOWED_SYSTEM_VARIABLES = Set.of(
            "lead_name", "first_name", "last_name", "phone", "email",
            "booking_time", "appointment_date", "ticket_id", "company_name", "amount", "reference_number"
    );

    public List<SmsTemplate> getTemplatesByBusiness(String businessId) {
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            List<SmsTemplate> list = templateRepository.findByTenant_Id(tenantId);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return templateRepository.findByBusinessId(businessId);
    }

    public Optional<SmsTemplate> getTemplateById(UUID id, String businessId) {
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            Optional<SmsTemplate> opt = templateRepository.findByIdAndTenant_Id(id, tenantId);
            if (opt.isPresent()) {
                return opt;
            }
        }
        return templateRepository.findByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public SmsTemplate saveTemplate(String businessId, SmsTemplate template) {
        template.setBusinessId(businessId);
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            template.setTenantId(tenantId);
        }

        // Extract and validate variables from template content
        Set<String> extractedVars = extractVariables(template.getContent());
        try {
            template.setAllowedVariables(objectMapper.writeValueAsString(extractedVars));
        } catch (Exception e) {
            log.error("Failed to serialize allowed variables for template", e);
        }

        return templateRepository.save(template);
    }

    @Transactional
    public void deleteTemplate(UUID id, String businessId) {
        getTemplateById(id, businessId).ifPresent(templateRepository::delete);
    }

    public Set<String> extractVariables(String content) {
        if (content == null || content.isBlank()) return Collections.emptySet();
        Set<String> vars = new HashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return vars;
    }

    public String renderTemplate(String templateContent, Map<String, String> contextValues) {
        if (templateContent == null) return "";
        if (contextValues == null || contextValues.isEmpty()) return templateContent;

        String rendered = templateContent;
        for (Map.Entry<String, String> entry : contextValues.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            rendered = rendered.replace(placeholder, value);
        }
        return rendered;
    }

    private UUID tryParseUuid(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return UUID.fromString(val.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
