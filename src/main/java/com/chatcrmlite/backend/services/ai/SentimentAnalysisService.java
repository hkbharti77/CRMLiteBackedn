package com.chatcrmlite.backend.services.ai;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class SentimentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MessageRepository messageRepository;

    private static final List<String> FRUSTRATED_KEYWORDS = Arrays.asList(
            "angry", "frustrated", "terrible", "worst", "useless", "refund", "scam", "fraud",
            "sue", "lawyer", "horrible", "waste", "unacceptable", "bad service", "hate",
            "disappointed", "complaint", "cheat", "cheating", "money back", "fake"
    );

    private static final List<String> URGENT_KEYWORDS = Arrays.asList(
            "urgent", "emergency", "asap", "immediately", "right now", "critical", "help now",
            "call me back", "call back", "important", "alert", "fast", "right away", "issue now"
    );

    private static final List<String> POSITIVE_KEYWORDS = Arrays.asList(
            "great", "awesome", "thank you", "thanks", "excellent", "amazing", "love", "good",
            "interested", "super", "perfect", "helpful", "nice", "happy", "wonderful", "yes please"
    );

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentResult {
        private Message.Sentiment sentiment;
        private Double sentimentScore; // -1.0 to +1.0
        private boolean escalated;
        private String escalationReason;
    }

    /**
     * Analyzes incoming message text, detects sentiment emotion,
     * updates message & contact, and auto-escalates if frustrated/urgent.
     */
    @Transactional
    public SentimentResult analyzeAndProcessMessage(Message message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return SentimentResult.builder()
                    .sentiment(Message.Sentiment.NEUTRAL)
                    .sentimentScore(0.0)
                    .escalated(false)
                    .build();
        }

        String text = message.getContent().toLowerCase(Locale.ROOT);
        Message.Sentiment sentiment = Message.Sentiment.NEUTRAL;
        double score = 0.0;
        boolean triggerEscalation = false;
        String reason = null;

        // Check Frustrated (Highest Priority)
        for (String kw : FRUSTRATED_KEYWORDS) {
            if (text.contains(kw)) {
                sentiment = Message.Sentiment.FRUSTRATED;
                score = -0.85;
                triggerEscalation = true;
                reason = "Customer message expressed frustration/dissatisfaction: '" + kw + "'";
                break;
            }
        }

        // Check Urgent if not frustrated
        if (sentiment == Message.Sentiment.NEUTRAL) {
            for (String kw : URGENT_KEYWORDS) {
                if (text.contains(kw)) {
                    sentiment = Message.Sentiment.URGENT;
                    score = 0.50;
                    triggerEscalation = true;
                    reason = "Customer message marked as high urgency: '" + kw + "'";
                    break;
                }
            }
        }

        // Check Positive if still neutral
        if (sentiment == Message.Sentiment.NEUTRAL) {
            for (String kw : POSITIVE_KEYWORDS) {
                if (text.contains(kw)) {
                    sentiment = Message.Sentiment.POSITIVE;
                    score = 0.80;
                    break;
                }
            }
        }

        // Attach sentiment result to Message
        message.setSentiment(sentiment);
        message.setSentimentScore(score);
        messageRepository.save(message);

        // Update Contact latest sentiment & execute escalation if triggered
        Contact contact = message.getContact();
        if (contact != null) {
            contact.setLatestSentiment(sentiment);
            if (triggerEscalation) {
                contact.setBotPaused(true); // Auto-pause AI Bot for human takeover
                contact.setEscalated(true);
                contact.setEscalatedAt(LocalDateTime.now());
                log.warn("[SentimentEscalation] Auto-escalating contact waId={} due to sentiment={}: {}",
                        contact.getWaId(), sentiment, reason);
            }
            contactRepository.save(contact);
        }

        return SentimentResult.builder()
                .sentiment(sentiment)
                .sentimentScore(score)
                .escalated(triggerEscalation)
                .escalationReason(reason)
                .build();
    }
}
