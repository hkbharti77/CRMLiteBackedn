package com.chatcrmlite.backend.services.memory;

import com.chatcrmlite.backend.dto.memory.ConversationContext;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import com.chatcrmlite.backend.models.memory.ConversationSessionState;
import com.chatcrmlite.backend.models.voice.VoiceTurn;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WebChatMessageRepository;
import com.chatcrmlite.backend.repositories.WebChatSessionRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final WebChatSessionRepository webChatSessionRepository;
    private final WebChatMessageRepository webChatMessageRepository;
    private final VoiceTurnRepository voiceTurnRepository;
    private final MessageRepository messageRepository;
    private final RagRouterService ragRouterService;
    
    private static final int MAX_HISTORY_TOKENS = 2500; // Preflight budget
    private static final int CHARS_PER_TOKEN = 4; // Approx

    public ConversationContext getWebChatContext(com.chatcrmlite.backend.models.User owner, String sessionId, String latestQuery) {
        WebChatSession session = webChatSessionRepository.findByOwnerAndSessionId(owner, sessionId).orElse(null);
        if (session == null) {
            return buildContext(sessionId, "WEB", "", latestQuery);
        }
        
        List<WebChatMessage> recentTurns = webChatMessageRepository.findTop50BySessionOrderByCreatedAtDesc(session);
        Collections.reverse(recentTurns); // Convert to chronological ASC
        
        String formattedHistory = buildFormattedHistoryWeb(recentTurns);
        return buildContext(session.getId().toString(), "WEB", formattedHistory, latestQuery);
    }

    public ConversationContext getVoiceContext(UUID sessionId, String latestQuery) {
        List<VoiceTurn> recentTurns = voiceTurnRepository.findTop50BySessionIdOrderByTurnNumberDesc(sessionId);
        Collections.reverse(recentTurns);
        
        String formattedHistory = buildFormattedHistoryVoice(recentTurns);
        ConversationContext context = buildContext(sessionId.toString(), "VOICE", formattedHistory, latestQuery);
        
        // Trigger async summary generation for voice (does not block TTS)
        generateRollingSummaryAsync(context);
        
        return context;
    }

    public ConversationContext getWhatsAppContext(Contact contact, String latestQuery) {
        // WhatsApp active context window (e.g. 24h conversational policy)
        Instant windowStart = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Message> recentTurns = messageRepository.findByContactAndTimestampAfterOrderByTimestampAsc(contact, windowStart);
        
        // Limit to 50 most recent if window has too many
        if (recentTurns.size() > 50) {
            recentTurns = recentTurns.subList(recentTurns.size() - 50, recentTurns.size());
        }
        
        String formattedHistory = buildFormattedHistoryWhatsApp(recentTurns);
        return buildContext(contact.getId().toString(), "WHATSAPP", formattedHistory, latestQuery);
    }

    private ConversationContext buildContext(String conversationId, String channel, String history, String query) {
        boolean requiresRag = ragRouterService.requiresRag(query);
        
        // Enforce max token budget for history
        String truncatedHistory = truncateToTokenBudget(history, MAX_HISTORY_TOKENS);
        
        ConversationSessionState state = new ConversationSessionState();
        state.setConversationId(conversationId);
        state.setChannel(channel);
        state.setLastActivity(Instant.now());
        
        return ConversationContext.builder()
                .formattedRecentTurns(truncatedHistory)
                .sessionState(state)
                .requiresRag(requiresRag)
                .latestQuery(query)
                .build();
    }

    private String buildFormattedHistoryWeb(List<WebChatMessage> turns) {
        return turns.stream()
                .map(t -> (t.getSender() == WebChatMessage.Sender.BOT ? "Assistant: " : "User: ") + t.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String buildFormattedHistoryVoice(List<VoiceTurn> turns) {
        return turns.stream()
                .map(t -> "User: " + t.getUserTranscript() + "\nAssistant: " + t.getBotResponseText())
                .collect(Collectors.joining("\n"));
    }

    private String buildFormattedHistoryWhatsApp(List<Message> turns) {
        return turns.stream()
                .map(t -> (t.getDirection() == Message.Direction.OUTGOING ? "Assistant: " : "User: ") + t.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return "";
        int maxChars = maxTokens * CHARS_PER_TOKEN;
        if (text.length() <= maxChars) return text;
        // Keep the most recent part (end of string)
        return "... " + text.substring(text.length() - maxChars);
    }

    @Async("taskExecutor")
    public void generateRollingSummaryAsync(ConversationContext context) {
        // Target: < 2000ms execution, runs fully in background without delaying voice TTS response
        log.debug("Background summarization started for session {}", context.getSessionState().getConversationId());
        // Call LLM in background to update summary version...
    }
}
