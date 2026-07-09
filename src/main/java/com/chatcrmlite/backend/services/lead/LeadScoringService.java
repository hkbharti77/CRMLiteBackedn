package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.LeadEnquiry;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.EmailTemplateService;
import com.chatcrmlite.backend.services.ReminderService;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadScoringService {

    private final AiOrchestrator aiOrchestrator;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final ReminderService reminderService;

    public void calculateAndEvaluate(Lead lead) {
        if (lead.getContact() == null) return;

        int score = calculateProgrammaticScore(lead);
        
        AiEvaluation aiEval = calculateAiScore(lead);
        if (aiEval.failed()) {
            score = fallbackRuleBasedScore(lead, score);
        } else {
            score += aiEval.score();
        }
        
        // Cap score at 100
        score = Math.min(score, 100);

        lead.setScore(score);
        lead.setInterestCategory(aiEval.interestCategory());

        log.info("Calculated Lead Score for {}: {}. Interest: {} (AI Failed: {})", 
                lead.getId(), score, aiEval.interestCategory(), aiEval.failed());
        
        evaluateAndTriggerActions(lead);
    }

    private int calculateProgrammaticScore(Lead lead) {
        int score = 0;
        Contact contact = lead.getContact();

        if (contact.getName() != null && !contact.getName().isBlank()) {
            score += 10;
        }
        if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
            score += 15;
        }
        if (contact.getWaId() != null && !contact.getWaId().isBlank()) {
            score += 15;
        }
        if (lead.getDealValue() != null && lead.getDealValue().compareTo(BigDecimal.ZERO) > 0) {
            score += 10;
        }
        return score;
    }

    private record AiEvaluation(int score, String interestCategory, boolean failed) {}

    private AiEvaluation calculateAiScore(Lead lead) {
        String enquiriesContext = lead.getEnquiryList().stream()
                .map(LeadEnquiry::getMessage)
                .collect(Collectors.joining("\n"));

        if (enquiriesContext.isBlank()) {
            return new AiEvaluation(0, "General", false);
        }

        String prompt = "Evaluate the following lead enquiries to determine buying intent, urgency, and interest category.\n" +
                "Enquiries: \n" + enquiriesContext + "\n\n" +
                "Provide the response in the following exact format:\n" +
                "SCORE: [0-50]\n" +
                "CATEGORY: [One word or short phrase representing the main product/service interest]";

        AiRequest request = AiRequest.builder()
                .prompt(prompt)
                .systemInstruction("You are a CRM AI assistant. You evaluate leads. Respond exactly in the requested format.")
                .temperature(0.2)
                .maxTokens(50)
                .complexity(AiRequest.TaskComplexity.LOW)
                .tenantId(lead.getOwner().getTenant().getId())
                .build();

        try {
            AiResponse response = aiOrchestrator.execute(request);
            return parseAiResponse(response.getContent());
        } catch (Exception e) {
            log.error("Failed to calculate AI score for lead " + lead.getId(), e);
            return new AiEvaluation(0, "General", true);
        }
    }

    private AiEvaluation parseAiResponse(String content) {
        int aiScore = 0;
        String category = "General";
        
        try {
            for (String line : content.split("\n")) {
                if (line.startsWith("SCORE:")) {
                    aiScore = Integer.parseInt(line.replace("SCORE:", "").trim());
                } else if (line.startsWith("CATEGORY:")) {
                    category = line.replace("CATEGORY:", "").trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI evaluation response: {}", content);
        }
        return new AiEvaluation(aiScore, category, false);
    }

    private int fallbackRuleBasedScore(Lead lead, int baseScore) {
        int fallbackScore = baseScore;
        String enquiriesContext = lead.getEnquiryList().stream()
                .map(LeadEnquiry::getMessage)
                .collect(Collectors.joining("\n")).toLowerCase();
                
        if (enquiriesContext.contains("buy") || enquiriesContext.contains("purchase") || enquiriesContext.contains("urgent") || enquiriesContext.contains("demo")) {
            fallbackScore += 30;
        } else if (enquiriesContext.contains("price") || enquiriesContext.contains("cost") || enquiriesContext.contains("quote")) {
            fallbackScore += 20;
        } else if (enquiriesContext.length() > 20) {
            fallbackScore += 10;
        }
        
        String niche = lead.getOwner().getBusinessType();
        if (niche != null) {
            switch (niche) {
                case "auto-used-car-dealers":
                    if (enquiriesContext.contains("test drive") || enquiriesContext.contains("mileage") || enquiriesContext.contains("car")) fallbackScore += 15;
                    break;
                case "dental-clinics":
                case "physiotherapy-chiropractic-centers":
                case "homeopathy-ayurveda-doctors":
                case "skin-aesthetic-clinics":
                    if (enquiriesContext.contains("appointment") || enquiriesContext.contains("pain") || enquiriesContext.contains("treatment") || enquiriesContext.contains("consultation")) fallbackScore += 15;
                    break;
                case "property-brokers":
                    if (enquiriesContext.contains("visit") || enquiriesContext.contains("apartment") || enquiriesContext.contains("property") || enquiriesContext.contains("rent")) fallbackScore += 15;
                    break;
                case "event-wedding-planners":
                case "wedding-portrait-photographers":
                    if (enquiriesContext.contains("wedding") || enquiriesContext.contains("event") || enquiriesContext.contains("booking") || enquiriesContext.contains("date")) fallbackScore += 15;
                    break;
                case "freelance-makeup-artists-mua":
                case "premium-salons-hair-clinics":
                    if (enquiriesContext.contains("bridal") || enquiriesContext.contains("hair") || enquiriesContext.contains("makeup") || enquiriesContext.contains("booking")) fallbackScore += 15;
                    break;
                case "independent-tutors":
                case "music-art-classes":
                case "career-study-abroad-counselors":
                    if (enquiriesContext.contains("class") || enquiriesContext.contains("course") || enquiriesContext.contains("admission") || enquiriesContext.contains("tutor")) fallbackScore += 15;
                    break;
                case "gym-personal-fitness-trainers":
                case "yoga-meditation-instructors":
                    if (enquiriesContext.contains("membership") || enquiriesContext.contains("trainer") || enquiriesContext.contains("join")) fallbackScore += 15;
                    break;
                case "premium-tour-travel-operators":
                    if (enquiriesContext.contains("package") || enquiriesContext.contains("tour") || enquiriesContext.contains("trip") || enquiriesContext.contains("flight")) fallbackScore += 15;
                    break;
                case "insurance-agents":
                    if (enquiriesContext.contains("policy") || enquiriesContext.contains("claim") || enquiriesContext.contains("premium") || enquiriesContext.contains("cover")) fallbackScore += 15;
                    break;
                case "interior-designers-architects":
                case "solar-panel-smart-home-installers":
                case "freelance-web-graphic-designers":
                    if (enquiriesContext.contains("design") || enquiriesContext.contains("project") || enquiriesContext.contains("installation") || enquiriesContext.contains("estimate")) fallbackScore += 15;
                    break;
            }
        }
        
        return fallbackScore;
    }

    private void evaluateAndTriggerActions(Lead lead) {
        int score = lead.getScore() != null ? lead.getScore() : 0;
        
        if (score >= 70 && lead.getContact().getEmail() != null) {
            sendAutomatedFollowback(lead);
        }
        
        if (score >= 80) {
            createInternalReminder(lead);
            emailService.sendHighValueLeadAlert(lead.getOwner().getEmail(), lead.getOwner().getDisplayName(), lead.getContact().getName(), score);
        }
    }

    private void sendAutomatedFollowback(Lead lead) {
        Optional<EmailTemplate> templateOpt = emailTemplateService.findTemplateForInterest(
                lead.getOwner().getTenant(), lead.getInterestCategory());

        if (templateOpt.isPresent()) {
            emailService.sendAutomatedFollowback(
                    lead.getContact().getEmail(),
                    lead.getContact().getName(),
                    lead.getOwner().getTenant().getBusinessName(),
                    templateOpt.get()
            );
        }
    }

    private void createInternalReminder(Lead lead) {
        Reminder reminder = new Reminder();
        reminder.setLead(lead);
        reminder.setOwner(lead.getOwner());
        reminder.setMessage("High value lead (Score: " + lead.getScore() + ") requires personal follow-up. Interest: " + lead.getInterestCategory());
        reminder.setDueDate(LocalDateTime.now().plusHours(24));
        reminderService.createReminder(reminder);
    }
}
