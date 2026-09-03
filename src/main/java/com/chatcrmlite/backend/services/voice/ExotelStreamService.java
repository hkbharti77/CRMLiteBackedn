package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.ai.SarvamVoiceService;
import com.chatcrmlite.backend.services.RagRetrievalService;
import com.chatcrmlite.backend.services.memory.ConversationMemoryService;
import com.chatcrmlite.backend.dto.memory.ConversationContext;
import com.chatcrmlite.backend.services.voice.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExotelStreamService {

    private final ConcurrentHashMap<String, ExotelCallSession> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final DeepgramVoiceService deepgramVoiceService;
    private final SarvamVoiceService sarvamVoiceService;
    private final RagRetrievalService ragRetrievalService;
    private final ConversationMemoryService conversationMemoryService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final SpeechNormalizer speechNormalizer;

    public ExotelCallSession createSession(String streamId, ExotelCallSession session) {
        activeSessions.put(streamId, session);
        log.info("Created new Exotel stream session: {}", streamId);
        
        // Start background processing pipeline for this session
        startSttPipeline(session);
        
        return session;
    }

    public ExotelCallSession getSession(String streamId) {
        return activeSessions.get(streamId);
    }

    public void removeSession(String streamId) {
        ExotelCallSession session = activeSessions.remove(streamId);
        if (session != null) {
            session.transitionTo(CallState.CLOSED);
            if (session.getCurrentTurn() != null) {
                session.getCurrentTurn().cancel();
            }
            log.info("Closed and removed Exotel stream session: {}", streamId);
        }
    }

    public void processInboundAudio(String streamId, AudioChunk chunk) {
        ExotelCallSession session = getSession(streamId);
        if (session == null || session.getState() == CallState.CLOSED) return;

        boolean added = session.getInboundAudioQueue().offer(chunk);
        if (!added) {
            log.warn("Inbound audio queue full for stream {}, dropping packet", streamId);
        }
    }

    public void handleBargeIn(String streamId) {
        ExotelCallSession session = getSession(streamId);
        if (session == null) return;

        if (session.getState() == CallState.SPEAKING || session.getState() == CallState.THINKING) {
            session.transitionTo(CallState.INTERRUPTED);
            if (session.getCurrentTurn() != null) {
                session.getCurrentTurn().cancel();
            }
            session.getOutboundAudioQueue().clear();
            log.info("Barge-in detected for stream {}. Cancelled turn and cleared outbound queue.", streamId);
            session.transitionTo(CallState.LISTENING);
        }
    }

    /**
     * STT Pipeline: Reads inbound audio queue, buffers until silence/barge-in, sends to STT.
     * Note: In a true streaming environment, this would send WebSockets to STT. Here we buffer
     * and use Deepgram REST API as a robust placeholder.
     */
    private void startSttPipeline(ExotelCallSession session) {
        executorService.submit(() -> {
            try {
                // Buffer for incoming audio
                java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream();
                long lastAudioTime = System.currentTimeMillis();
                
                while (session.getState() != CallState.CLOSED) {
                    AudioChunk chunk = session.getInboundAudioQueue().poll(100, TimeUnit.MILLISECONDS);
                    
                    if (chunk != null) {
                        audioBuffer.write(chunk.data());
                        lastAudioTime = System.currentTimeMillis();
                        
                        // Very simple barge-in VAD placeholder: if we are speaking and get audio, interrupt
                        if (session.getState() == CallState.SPEAKING) {
                            handleBargeIn(session.getStreamId());
                        }
                    } else if (audioBuffer.size() > 16000 && (System.currentTimeMillis() - lastAudioTime > 800)) {
                        // 800ms silence detected after speech -> End of speech
                        byte[] finalAudio = audioBuffer.toByteArray();
                        audioBuffer.reset();
                        
                        if (session.getState() == CallState.LISTENING || session.getState() == CallState.IDLE) {
                            session.setLastSpeechEndMs(System.currentTimeMillis());
                            session.transitionTo(CallState.THINKING);
                            
                            // Process STT -> LLM -> TTS in a new turn
                            processConversationTurn(session, finalAudio);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in STT pipeline", e);
            }
        });
    }

    /**
     * Conversation Pipeline: STT -> LLM -> Chunking -> TTS
     */
    private void processConversationTurn(ExotelCallSession session, byte[] audioBytes) {
        executorService.submit(() -> {
            ConversationTurn turn = new ConversationTurn();
            session.setCurrentTurn(turn);
            AtomicBoolean cancelToken = turn.getCancellationToken();

            try {
                // 1. STT
                if (cancelToken.get()) return;
                long sttStart = System.currentTimeMillis();
                DeepgramVoiceService.DeepgramTranscriptionResult sttRes = 
                    deepgramVoiceService.transcribeAudio(audioBytes, "audio/L16", "en"); // Assuming linear16 or mulaw encoded raw
                session.setSttFinalMs(System.currentTimeMillis());
                
                String transcript = sttRes.getTranscript();
                if (transcript == null || transcript.isBlank()) {
                    session.transitionTo(CallState.LISTENING);
                    return; // No speech detected
                }
                log.info("Transcript: {}", transcript);

                // 2. LLM / RAG (Simulating token streaming by just getting response for now,
                // to fully stream we would need reactive LLM client, but chunker handles text stream)
                if (cancelToken.get()) return;
                session.setLlmFirstTokenMs(System.currentTimeMillis());
                
                // UUID placeholder - in reality fetched from Call metadata
                UUID businessId = UUID.fromString("00000000-0000-0000-0000-000000000000"); 
                
                ConversationContext memContext = conversationMemoryService.getVoiceContext(UUID.randomUUID(), transcript);
                
                com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext toolContext = 
                    new com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext(
                        businessId, businessId, UUID.randomUUID(), session.getStreamId(), 
                        session.getStreamId(), UUID.randomUUID().toString(), "+919999999999"
                    );

                String botResponseText = conversationOrchestrator.executeTurn(
                        "You are Priya, a helpful voice assistant. You can help users book appointments and create leads. Answer concisely.", 
                        transcript, 
                        List.of(), // TODO: use real ChatMessage history
                        toolContext
                );
                
                if (cancelToken.get()) return;

                // 3. Sentence Chunking & TTS Pipeline
                session.transitionTo(CallState.SPEAKING);
                SentenceChunker chunker = new SentenceChunker();
                List<String> sentences = chunker.accept(botResponseText);
                sentences.addAll(chunker.flush());

                boolean first = true;
                for (String sentence : sentences) {
                    if (cancelToken.get()) break;
                    
                    String speakableText = speechNormalizer.normalize(sentence);
                    
                    // 4. TTS (using Deepgram/Sarvam)
                    // Synthesize chunk
                    byte[] ttsAudio = deepgramVoiceService.synthesizeSpeech(speakableText, "aura-stella-en");
                    
                    if (first) {
                        session.setFirstSentenceReadyMs(System.currentTimeMillis());
                        session.setTtsFirstAudioMs(System.currentTimeMillis());
                        first = false;
                    }

                    if (cancelToken.get()) break;

                    // Push to outbound queue
                    AudioChunk outChunk = new AudioChunk(ttsAudio, "mp3", 24000, 1, System.currentTimeMillis(), false);
                    session.getOutboundAudioQueue().offer(outChunk);
                }

                if (!cancelToken.get()) {
                    session.transitionTo(CallState.LISTENING);
                }
                
            } catch (Exception e) {
                log.error("Error processing conversation turn", e);
                session.transitionTo(CallState.LISTENING);
            }
        });
    }
}
