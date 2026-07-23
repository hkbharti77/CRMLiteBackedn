package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.LeadEnquiry;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.EmailTemplateService;
import com.chatcrmlite.backend.services.ReminderService;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
    @Autowired private LeadRepository leadRepository;
    @Autowired private MessageRepository messageRepository;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeadScoreResult {
        private Integer totalScore; // 0 to 100
        private Lead.ScoreGrade scoreGrade; // HOT, WARM, COLD
        private int interactionScore; // max 30
        private int sentimentScore;   // max 30
        private int dealValueScore;   // max 25
        private int profileScore;     // max 15
        private LocalDateTime calculatedAt;
    }

    /**
     * Calculates dynamic quality score (0–100) for a lead and updates Lead entity.
     */
    @Transactional
    public LeadScoreResult calculateAndUpdateLeadScore(Lead lead) {
        if (lead == null) {
            return LeadScoreResult.builder()
                    .totalScore(0)
                    .scoreGrade(Lead.ScoreGrade.COLD)
                    .calculatedAt(LocalDateTime.now())
                    .build();
        }

        Contact contact = lead.getContact();

        // 1. Interaction Frequency Score (Max 30 pts)
        int interactionScore = 0;
        if (contact != null) {
            List<Message> messages = messageRepository.findAllByContactOrderByTimestampAsc(contact);
            int count = messages != null ? messages.size() : 0;
            if (count >= 10) {
                interactionScore = 30;
            } else if (count >= 5) {
                interactionScore = 20;
            } else if (count >= 2) {
                interactionScore = 10;
            } else if (count >= 1) {
                interactionScore = 5;
            }
        }

        // 2. Sentiment Tone Score (Max 30 pts)
        int sentimentScore = 15; // default neutral
        if (contact != null && contact.getLatestSentiment() != null) {
            switch (contact.getLatestSentiment()) {
                case POSITIVE:
                    sentimentScore = 30;
                    break;
                case URGENT:
                    sentimentScore = 22; // High intent but needs fast response
                    break;
                case NEUTRAL:
                    sentimentScore = 15;
                    break;
                case FRUSTRATED:
                    sentimentScore = 0;
                    break;
            }
        }

        // 3. Deal Value Score (Max 25 pts)
        int dealValueScore = 0;
        BigDecimal val = lead.getDealValue();
        if (val != null) {
            double doubleVal = val.doubleValue();
            if (doubleVal >= 50000) {
                dealValueScore = 25;
            } else if (doubleVal >= 10000) {
                dealValueScore = 18;
            } else if (doubleVal > 0) {
                dealValueScore = 10;
            }
        }

        // 4. Engagement & Profile Completeness (Max 15 pts)
        int profileScore = 0;
        if (contact != null && contact.getEmail() != null && !contact.getEmail().isBlank()) {
            profileScore += 5;
        }
        if (lead.getStatus() == Lead.LeadStatus.INTERESTED || lead.getStatus() == Lead.LeadStatus.BOOKED) {
            profileScore += 10;
        } else if (lead.getStatus() == Lead.LeadStatus.FOLLOW_UP) {
            profileScore += 5;
        }

        int total = Math.min(100, interactionScore + sentimentScore + dealValueScore + profileScore);

        Lead.ScoreGrade grade;
        if (total >= 75) {
            grade = Lead.ScoreGrade.HOT;
        } else if (total >= 45) {
            grade = Lead.ScoreGrade.WARM;
        } else {
            grade = Lead.ScoreGrade.COLD;
        }

        // Update Lead entity
        lead.setScore(total);
        lead.setScoreGrade(grade);
        lead.setLastScoredAt(LocalDateTime.now());
        if (leadRepository != null) {
            leadRepository.save(lead);
        }

        log.info("[LeadScoring] Calculated score={} grade={} for leadId={}", total, grade, lead.getId());

        return LeadScoreResult.builder()
                .totalScore(total)
                .scoreGrade(grade)
                .interactionScore(interactionScore)
                .sentimentScore(sentimentScore)
                .dealValueScore(dealValueScore)
                .profileScore(profileScore)
                .calculatedAt(lead.getLastScoredAt())
                .build();
    }

    public void calculateAndEvaluate(Lead lead) {
        if (lead.getContact() == null) return;

        calculateAndUpdateLeadScore(lead);

        AiEvaluation aiEval = calculateAiScore(lead);
        if (!aiEval.failed() && aiEval.interestCategory() != null) {
            lead.setInterestCategory(aiEval.interestCategory());
        }

        log.info("Evaluated Lead Score for {}: {}. Interest: {} (AI Failed: {})", 
                lead.getId(), lead.getScore(), aiEval.interestCategory(), aiEval.failed());
        
        evaluateAndTriggerActions(lead);
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
