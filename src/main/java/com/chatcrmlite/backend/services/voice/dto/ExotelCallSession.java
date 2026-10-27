package com.chatcrmlite.backend.services.voice.dto;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

@Data
public class ExotelCallSession {
    private final String callId;
    private final String streamId;
    private final String phoneNumber;
    private final String language;
    private final WebSocketSession webSocketSession;

    private AtomicReference<CallState> state = new AtomicReference<>(CallState.IDLE);
    private ConversationTurn currentTurn;
    
    // Bounded queues for backpressure (e.g., max 50 chunks)
    private final BlockingQueue<AudioChunk> inboundAudioQueue = new LinkedBlockingQueue<>(50);
    private final BlockingQueue<AudioChunk> outboundAudioQueue = new LinkedBlockingQueue<>(50);

    // Telemetry timestamps
    private long lastSpeechEndMs;
    private long sttFinalMs;
    private long llmFirstTokenMs;
    private long firstSentenceReadyMs;
    private long ttsFirstAudioMs;

    public ExotelCallSession(String callId, String streamId, String phoneNumber, String language, WebSocketSession webSocketSession) {
        this.callId = callId;
        this.streamId = streamId;
        this.phoneNumber = phoneNumber;
        this.language = language;
        this.webSocketSession = webSocketSession;
    }

    public void transitionTo(CallState newState) {
        this.state.set(newState);
    }
    
    public CallState getState() {
        return this.state.get();
    }

    public String getCallId() { return callId; }
    public String getStreamId() { return streamId; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getLanguage() { return language; }
    public WebSocketSession getWebSocketSession() { return webSocketSession; }

    public ConversationTurn getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(ConversationTurn currentTurn) { this.currentTurn = currentTurn; }

    public BlockingQueue<AudioChunk> getInboundAudioQueue() { return inboundAudioQueue; }
    public BlockingQueue<AudioChunk> getOutboundAudioQueue() { return outboundAudioQueue; }

    public long getLastSpeechEndMs() { return lastSpeechEndMs; }
    public void setLastSpeechEndMs(long lastSpeechEndMs) { this.lastSpeechEndMs = lastSpeechEndMs; }

    public long getSttFinalMs() { return sttFinalMs; }
    public void setSttFinalMs(long sttFinalMs) { this.sttFinalMs = sttFinalMs; }

    public long getLlmFirstTokenMs() { return llmFirstTokenMs; }
    public void setLlmFirstTokenMs(long llmFirstTokenMs) { this.llmFirstTokenMs = llmFirstTokenMs; }

    public long getFirstSentenceReadyMs() { return firstSentenceReadyMs; }
    public void setFirstSentenceReadyMs(long firstSentenceReadyMs) { this.firstSentenceReadyMs = firstSentenceReadyMs; }

    public long getTtsFirstAudioMs() { return ttsFirstAudioMs; }
    public void setTtsFirstAudioMs(long ttsFirstAudioMs) { this.ttsFirstAudioMs = ttsFirstAudioMs; }
}
