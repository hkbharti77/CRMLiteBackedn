package com.chatcrmlite.backend.services.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enterprise-grade real-time Bi-directional WebSocket STT client for Deepgram Nova-2.
 * Streams raw linear16 48kHz mono PCM audio chunks asynchronously and receives
 * interim and speech-final transcriptions with sub-250ms latency.
 */
@Slf4j
public class DeepgramLiveSttSession {

    private final String apiKey;
    private final String model;
    private final boolean smartFormat;
    private final boolean punctuate;
    private final String language;
    private final String callId;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean isSending = new AtomicBoolean(false);
    private final AtomicBoolean hasSpeechFinal = new AtomicBoolean(false);

    private final StringBuilder finalTranscriptBuffer = new StringBuilder();
    private volatile String interimTranscript = "";
    private volatile double lastConfidence = 0.0;
    private final AtomicLong bytesStreamed = new AtomicLong(0);

    private final Queue<ByteBuffer> outboundAudioQueue = new ConcurrentLinkedQueue<>();
    private WebSocket webSocket;

    private volatile Consumer<String> interimListener;
    private volatile Consumer<String> speechFinalListener;

    public DeepgramLiveSttSession(String apiKey,
                                 String model,
                                 boolean smartFormat,
                                 boolean punctuate,
                                 String language,
                                 String callId,
                                 ObjectMapper objectMapper) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model : "nova-2";
        this.smartFormat = smartFormat;
        this.punctuate = punctuate;
        this.language = (language != null && !language.isBlank()) ? language : "en-IN";
        this.callId = callId;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        
        connectAsync();
    }

    public void setInterimListener(Consumer<String> interimListener) {
        this.interimListener = interimListener;
    }

    public void setSpeechFinalListener(Consumer<String> speechFinalListener) {
        this.speechFinalListener = speechFinalListener;
    }

    public boolean isConnected() {
        return connected.get() && !closed.get();
    }

    public boolean hasSpeechFinal() {
        return hasSpeechFinal.get();
    }

    public double getLastConfidence() {
        return lastConfidence;
    }

    public long getBytesStreamed() {
        return bytesStreamed.get();
    }

    /**
     * Connects asynchronously to Deepgram WebSocket live listening endpoint.
     */
    private void connectAsync() {
        if (apiKey.isBlank()) {
            log.warn("⚠️ [DeepgramLiveSTT] No API key provided for callId={}, skipping live STT connection", callId);
            return;
        }

        try {
            String wsUrl = String.format(
                    "wss://api.deepgram.com/v1/listen?model=%s&encoding=linear16&sample_rate=48000&channels=1&smart_format=%b&punctuate=%b&interim_results=true&endpointing=300&utterance_end_ms=1000&language=%s",
                    model, smartFormat, punctuate, language
            );

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            log.info("🎙️ [DeepgramLiveSTT] Connecting live WebSocket STT for callId={} (model={}, lang={})",
                    callId, model, language);

            httpClient.newWebSocketBuilder()
                    .header("Authorization", "Token " + apiKey)
                    .buildAsync(URI.create(wsUrl), new DeepgramWebSocketListener())
                    .whenComplete((ws, ex) -> {
                        if (ex != null) {
                            log.error("❌ [DeepgramLiveSTT] WebSocket connection failed for callId={}: {}", callId, ex.getMessage());
                            connected.set(false);
                        } else {
                            this.webSocket = ws;
                            this.connected.set(true);
                            log.info("✅ [DeepgramLiveSTT] Connected live WebSocket STT successfully for callId={}", callId);
                            drainAudioQueue();
                        }
                    });
        } catch (Exception e) {
            log.error("❌ [DeepgramLiveSTT] Error initializing WebSocket for callId={}: {}", callId, e.getMessage(), e);
            connected.set(false);
        }
    }

    /**
     * Streams a linear16 48kHz PCM audio frame to Deepgram.
     * Audio frames are queued in order and drained sequentially to respect WebSocket concurrency rules.
     */
    public void sendAudioChunk(byte[] pcmChunk) {
        if (closed.get() || pcmChunk == null || pcmChunk.length == 0) return;

        bytesStreamed.addAndGet(pcmChunk.length);

        // Cap queue to 50 frames (~1 sec) to prevent stale accumulation if network stalls
        while (outboundAudioQueue.size() > 50) {
            outboundAudioQueue.poll();
        }

        outboundAudioQueue.offer(ByteBuffer.wrap(pcmChunk));
        if (connected.get()) {
            drainAudioQueue();
        }
    }

    private void drainAudioQueue() {
        if (!connected.get() || webSocket == null || closed.get()) return;

        if (isSending.compareAndSet(false, true)) {
            sendNextFrame();
        }
    }

    private void sendNextFrame() {
        if (!connected.get() || webSocket == null || closed.get()) {
            isSending.set(false);
            return;
        }

        ByteBuffer buffer = outboundAudioQueue.poll();
        if (buffer == null) {
            isSending.set(false);
            if (!outboundAudioQueue.isEmpty() && connected.get()) {
                drainAudioQueue();
            }
            return;
        }

        try {
            webSocket.sendBinary(buffer, true).whenComplete((ws, ex) -> {
                if (ex != null) {
                    log.debug("⚠️ [DeepgramLiveSTT] sendBinary failed for callId={}: {}", callId, ex.getMessage());
                }
                sendNextFrame();
            });
        } catch (Exception e) {
            log.debug("⚠️ [DeepgramLiveSTT] Exception initiating sendBinary: {}", e.getMessage());
            isSending.set(false);
        }
    }

    /**
     * Gets current transcript without clearing it.
     */
    public String getAccumulatedTranscript() {
        synchronized (finalTranscriptBuffer) {
            String finals = finalTranscriptBuffer.toString().trim();
            if (!finals.isEmpty()) {
                return finals;
            }
            return interimTranscript.trim();
        }
    }

    /**
     * Retrieves the complete transcribed utterance accumulated so far and clears the buffer
     * for the next conversation turn.
     */
    public String getAndResetTranscript() {
        synchronized (finalTranscriptBuffer) {
            String result = finalTranscriptBuffer.toString().trim();
            if (result.isEmpty() && !interimTranscript.isBlank()) {
                result = interimTranscript.trim();
            }
            finalTranscriptBuffer.setLength(0);
            interimTranscript = "";
            hasSpeechFinal.set(false);
            return result;
        }
    }

    /**
     * Sends keepalive ping to keep connection open during user thinking / AI speaking periods.
     */
    public void sendKeepAlive() {
        if (connected.get() && webSocket != null && !closed.get()) {
            try {
                webSocket.sendText("{\"type\":\"KeepAlive\"}", true);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Closes the live streaming STT session gracefully.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            if (webSocket != null) {
                try {
                    webSocket.sendText("{\"type\":\"CloseStream\"}", true);
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Call Ended");
                } catch (Exception ignored) {}
            }
            outboundAudioQueue.clear();
            log.info("🔌 [DeepgramLiveSTT] Closed live STT session for callId={} (TotalBytesStreamed={})",
                    callId, bytesStreamed.get());
        }
    }

    private class DeepgramWebSocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket ws) {
            connected.set(true);
            log.info("🌐 [DeepgramLiveSTT] WebSocket onOpen received for callId={}", callId);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            try {
                String payload = data.toString();
                JsonNode root = objectMapper.readTree(payload);

                String type = root.path("type").asText("");
                if ("Results".equalsIgnoreCase(type) || root.has("channel")) {
                    boolean isFinal = root.path("is_final").asBoolean(false);
                    boolean speechFinal = root.path("speech_final").asBoolean(false);

                    JsonNode alt = root.path("channel").path("alternatives").path(0);
                    String transcript = alt.path("transcript").asText("").trim();
                    double confidence = alt.path("confidence").asDouble(0.0);

                    if (!transcript.isEmpty()) {
                        if (isFinal) {
                            synchronized (finalTranscriptBuffer) {
                                if (finalTranscriptBuffer.length() > 0) {
                                    finalTranscriptBuffer.append(" ");
                                }
                                finalTranscriptBuffer.append(transcript);
                            }
                            lastConfidence = confidence;
                            interimTranscript = "";
                            log.info("🎙️ [DeepgramLiveSTT] is_final transcript for callId={}: '{}' (conf={})",
                                    callId, transcript, String.format("%.2f", confidence));
                        } else {
                            interimTranscript = transcript;
                            Consumer<String> il = interimListener;
                            if (il != null) {
                                il.accept(transcript);
                            }
                        }
                    }

                    if (speechFinal) {
                        hasSpeechFinal.set(true);
                        String fullText = getAccumulatedTranscript();
                        log.info("⚡ [DeepgramLiveSTT] speech_final detected for callId={}: '{}'", callId, fullText);
                        Consumer<String> sfl = speechFinalListener;
                        if (sfl != null && !fullText.isBlank()) {
                            sfl.accept(fullText);
                        }
                    }
                } else if ("UtteranceEnd".equalsIgnoreCase(type)) {
                    hasSpeechFinal.set(true);
                    log.info("⏹️ [DeepgramLiveSTT] UtteranceEnd event received for callId={}", callId);
                }
            } catch (Exception e) {
                log.warn("⚠️ [DeepgramLiveSTT] Error parsing incoming STT message for callId={}: {}",
                        callId, e.getMessage());
            }

            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("⚠️ [DeepgramLiveSTT] WebSocket error for callId={}: {}", callId, error.getMessage());
            connected.set(false);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("🔌 [DeepgramLiveSTT] WebSocket closed for callId={}: status={} reason={}",
                    callId, statusCode, reason);
            connected.set(false);
            return CompletableFuture.completedFuture(null);
        }
    }
}
