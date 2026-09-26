package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enterprise Native Java WebRTC Media Gateway for WhatsApp Business Calling.
 * Handles UDP socket lifecycle, STUN Binding responses, DTLS 1.2, SRTP encryption/decryption, and AI audio streaming.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebRtcMediaGateway {

    public enum CallMediaState {
        CREATED,
        ICE_CHECKING,
        ICE_CONNECTED,
        DTLS_HANDSHAKING,
        DTLS_CONNECTED,
        SRTP_READY,
        MEDIA_ACTIVE,
        FAILED,
        TERMINATED
    }

    private final WhatsAppVoiceCallBridgeService voiceCallBridgeService;
    private final DeepgramVoiceService deepgramVoiceService;
    private final WebRtcDtlsHandler dtlsHandler;

    @Value("${crmlite.calling.media.port-range-start:50000}")
    private int portRangeStart;

    @Value("${crmlite.calling.media.public-ip:127.0.0.1}")
    private String publicMediaIp;

    @Value("${crmlite.calling.turn.enabled:false}")
    private boolean turnEnabled;

    @Value("${crmlite.calling.turn.stun-host:stun.relay.metered.ca}")
    private String stunHost;

    @Value("${crmlite.calling.turn.stun-port:80}")
    private int stunPort;

    @Value("${crmlite.calling.turn.relay-host:}")
    private String relayHost;

    @Value("${crmlite.calling.turn.relay-port:50000}")
    private int relayPort;

    @Value("${crmlite.calling.timeout.dtls-ms:15000}")
    private int dtlsTimeoutMs;

    @Value("${crmlite.calling.timeout.media-start-ms:10000}")
    private int mediaStartTimeoutMs;

    private volatile String resolvedPublicIp = null;

    private final Map<String, WebRtcMediaSession> activeSessions = new ConcurrentHashMap<>();
    /** Survives terminateSession so Meta 138021 can be remapped to the real DTLS/RTP cause. */
    private final Map<String, String> lastFailureReasons = new ConcurrentHashMap<>();
    private final Map<String, CallMediaState> lastMediaStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private String getPublicMediaIp() {
        if (publicMediaIp != null && !publicMediaIp.isBlank() && !"127.0.0.1".equals(publicMediaIp) && !"localhost".equalsIgnoreCase(publicMediaIp)) {
            return publicMediaIp;
        }
        if (resolvedPublicIp != null) return resolvedPublicIp;

        try {
            java.net.URL url = new java.net.URL("https://api.ipify.org");
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(url.openStream()))) {
                String ip = in.readLine().trim();
                if (ip.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                    resolvedPublicIp = ip;
                    log.info("🌐 [WebRtcGateway] Auto-resolved public media IP: {}", resolvedPublicIp);
                    return resolvedPublicIp;
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [WebRtcGateway] Could not auto-detect public IP (using fallback): {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    /**
     * Initializes a media session for an incoming/outgoing WhatsApp call and generates the WebRTC SDP answer.
     */
    public String createMediaSession(String callId, UUID tenantId, String fromWaId, String sdpOffer) {
        log.info("🎙️ [WebRtcGateway] Creating WebRTC media session for callId={}", callId);
        if (sdpOffer != null) {
            log.info("📄 [WebRtcGateway] Meta Remote SDP Offer for callId={}:\n{}", callId, sdpOffer);
        }

        terminateSession(callId);

        DatagramSocket socket = allocateSocket();
        int localPort = socket.getLocalPort();

        InetSocketAddress srflxCandidate = discoverSrflxCandidate(socket);
        String effectiveIp = (srflxCandidate != null) ? srflxCandidate.getHostString() : getPublicMediaIp();

        InetSocketAddress remoteMetaAddress = parseRemoteMetaCandidate(sdpOffer);
        log.info("🎯 [WebRtcGateway] Target Meta Remote Candidate: {}", remoteMetaAddress);

        String mid = parseAttribute(sdpOffer, "(?m)^a=mid:(\\S+)", "audio");
        String opusPt = parseAttribute(sdpOffer, "(?m)^a=rtpmap:(\\d+)\\s+opus/48000/2", "111");
        String remoteUfrag = parseAttribute(sdpOffer, "(?m)^a=ice-ufrag:(\\S+)", null);
        String remotePwd = parseAttribute(sdpOffer, "(?m)^a=ice-pwd:(\\S+)", null);
        String remoteFingerprint = parseAttribute(sdpOffer, "(?m)^a=fingerprint:SHA-256\\s+(\\S+)", null);
        boolean remoteIceLite = sdpOffer != null && Pattern.compile("(?m)^a=ice-lite\\s*$").matcher(sdpOffer).find();
        // Meta WhatsApp media offers are ICE-lite → full ICE side must be CONTROLLING
        boolean iceControlling = remoteIceLite;
        if (!remoteIceLite) {
            // Still prefer controlling for outbound nomination toward Meta relays
            iceControlling = true;
        }
        log.info("🧊 [WebRtcGateway] ICE role: remoteIceLite={} localRole={}",
                remoteIceLite, iceControlling ? "CONTROLLING" : "CONTROLLED");

        long sessionId = Math.abs((long) callId.hashCode() + 1000000000L);
        String ufrag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String pwd = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String localFingerprint = dtlsHandler.getSha256Fingerprint();

        WebRtcMediaSession session = new WebRtcMediaSession(
                callId, tenantId, fromWaId, socket, remoteMetaAddress, Integer.parseInt(opusPt),
                ufrag, pwd, remoteUfrag, remotePwd, remoteFingerprint, localFingerprint, iceControlling
        );
        session.state = CallMediaState.CREATED;
        activeSessions.put(callId, session);

        startInboundListener(session);

        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- ").append(sessionId).append(" 2 IN IP4 127.0.0.1\r\n");
        sdp.append("s=CRMLite WebRTC Engine\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE ").append(mid).append("\r\n");
        sdp.append("a=msid-semantic: WMS\r\n");
        sdp.append("m=audio ").append(localPort).append(" UDP/TLS/RTP/SAVPF ").append(opusPt).append("\r\n");
        sdp.append("c=IN IP4 ").append(effectiveIp).append("\r\n");
        sdp.append("a=rtcp:9 IN IP4 0.0.0.0\r\n");
        sdp.append("a=ice-ufrag:").append(ufrag).append("\r\n");
        sdp.append("a=ice-pwd:").append(pwd).append("\r\n");
        sdp.append("a=ice-options:trickle\r\n");
        sdp.append("a=fingerprint:SHA-256 ").append(localFingerprint).append("\r\n");
        sdp.append("a=setup:active\r\n");
        sdp.append("a=mid:").append(mid).append("\r\n");
        sdp.append("a=rtcp-mux\r\n");
        sdp.append("a=rtcp-rsize\r\n");
        sdp.append("a=sendrecv\r\n");
        sdp.append("a=rtpmap:").append(opusPt).append(" opus/48000/2\r\n");
        sdp.append("a=fmtp:").append(opusPt).append(" maxaveragebitrate=20000;maxplaybackrate=16000;minptime=20;sprop-maxcapturerate=16000;useinbandfec=1\r\n");
        sdp.append("a=ptime:20\r\n");
        sdp.append("a=maxptime:20\r\n");
        sdp.append("a=candidate:1 1 UDP 2130706431 ").append(effectiveIp).append(" ").append(localPort).append(" typ host\r\n");
        if (srflxCandidate != null && !srflxCandidate.getHostString().equals(effectiveIp)) {
            sdp.append("a=candidate:2 1 UDP 1694498815 ").append(srflxCandidate.getHostString()).append(" ").append(srflxCandidate.getPort())
               .append(" typ srflx raddr ").append(effectiveIp).append(" rport ").append(localPort).append("\r\n");
        }
        if (turnEnabled && relayHost != null && !relayHost.isBlank()) {
            try {
                InetAddress relayAddr = InetAddress.getByName(relayHost);
                sdp.append("a=candidate:3 1 UDP 16777215 ").append(relayAddr.getHostAddress()).append(" ").append(relayPort)
                   .append(" typ relay raddr ").append(effectiveIp).append(" rport ").append(localPort).append("\r\n");
            } catch (Exception e) {
                log.debug("Could not resolve TURN relay host for candidate: {}", e.getMessage());
            }
        }
        sdp.append("a=end-of-candidates\r\n");

        String sdpAnswer = sdp.toString();
        session.serializedSdpAnswer = sdpAnswer;

        // Verify the ACTUAL serialized SDP (not internal variables)
        log.info("📄 [WebRtcGateway] EXACT SDP ANSWER sent to Meta for callId={}:\n{}", callId, sdpAnswer);
        verifySdpAnswer(sdpAnswer, localFingerprint, opusPt, mid);

        return sdpAnswer;
    }

    private void verifySdpAnswer(String sdpAnswer, String expectedFingerprint, String expectedOpusPt, String expectedMid) {
        boolean setupActive = sdpAnswer.contains("a=setup:active");
        boolean hasFingerprint = sdpAnswer.matches("(?s).*a=fingerprint:SHA-256\\s+" + Pattern.quote(expectedFingerprint) + ".*");
        boolean hasUfrag = Pattern.compile("(?m)^a=ice-ufrag:\\S+").matcher(sdpAnswer).find();
        boolean hasPwd = Pattern.compile("(?m)^a=ice-pwd:\\S+").matcher(sdpAnswer).find();
        boolean hasCandidate = Pattern.compile("(?m)^a=candidate:").matcher(sdpAnswer).find();
        boolean hasMid = sdpAnswer.contains("a=mid:" + expectedMid);
        boolean sendrecv = sdpAnswer.contains("a=sendrecv");
        boolean rtcpMux = sdpAnswer.contains("a=rtcp-mux");
        boolean opus = sdpAnswer.contains("a=rtpmap:" + expectedOpusPt + " opus/48000/2");

        log.info("📄 [WebRtcGateway] SDP_ANSWER_VERIFY setup:active={} fingerprintMatch={} ice-ufrag={} ice-pwd={} candidate={} mid={} sendrecv={} rtcp-mux={} opus={}",
                setupActive, hasFingerprint, hasUfrag, hasPwd, hasCandidate, hasMid, sendrecv, rtcpMux, opus);

        if (!setupActive || !hasFingerprint || !hasUfrag || !hasPwd || !hasCandidate || !hasMid || !sendrecv || !rtcpMux || !opus) {
            log.error("❌ [WebRtcGateway] SDP answer failed required attribute verification");
        }
    }

    private InetSocketAddress discoverSrflxCandidate(DatagramSocket socket) {
        String targetHost = (stunHost != null && !stunHost.isBlank()) ? stunHost : "stun.relay.metered.ca";
        int targetPort = (stunPort > 0) ? stunPort : 80;

        InetSocketAddress candidate = queryStunServer(socket, targetHost, targetPort);
        if (candidate == null) {
            candidate = queryStunServer(socket, "stun.l.google.com", 19302);
        }
        return candidate;
    }

    private InetSocketAddress queryStunServer(DatagramSocket socket, String host, int port) {
        try {
            InetSocketAddress stunServer = new InetSocketAddress(host, port);
            byte[] txId = new byte[12];
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);

            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) 0x0001);
            req.putShort((short) 0x0000);
            req.putInt(0x2112A442);
            req.put(txId);

            byte[] reqBytes = req.array();
            DatagramPacket reqPacket = new DatagramPacket(reqBytes, reqBytes.length, stunServer);
            socket.send(reqPacket);

            byte[] resBuffer = new byte[512];
            DatagramPacket resPacket = new DatagramPacket(resBuffer, resBuffer.length);
            socket.setSoTimeout(1000);
            try {
                socket.receive(resPacket);
                byte[] data = resPacket.getData();
                if (data.length >= 20 && data[0] == 0x01 && data[1] == 0x01) {
                    int offset = 20;
                    while (offset + 4 <= resPacket.getLength()) {
                        int attrType = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                        int attrLen = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                        if (attrType == 0x0020 && offset + 4 + attrLen <= resPacket.getLength()) {
                            int family = data[offset + 5] & 0xFF;
                            if (family == 0x01) {
                                int xorPort = ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);
                                int mappedPort = xorPort ^ (0x2112A442 >> 16);
                                int b1 = (data[offset + 8] & 0xFF) ^ 0x21;
                                int b2 = (data[offset + 9] & 0xFF) ^ 0x12;
                                int b3 = (data[offset + 10] & 0xFF) ^ 0xA4;
                                int b4 = (data[offset + 11] & 0xFF) ^ 0x42;
                                String mappedIp = b1 + "." + b2 + "." + b3 + "." + b4;
                                InetSocketAddress srflx = new InetSocketAddress(mappedIp, mappedPort);
                                log.info("🌐 [WebRtcGateway] STUN ({}:{}) resolved srflx candidate: {}", host, port, srflx);
                                return srflx;
                            }
                        }
                        offset += 4 + attrLen;
                        if (attrLen % 4 != 0) {
                            offset += (4 - (attrLen % 4));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] STUN ({}:{}) response timeout: {}", host, port, e.getMessage());
            } finally {
                socket.setSoTimeout(0);
            }
        } catch (Exception e) {
            log.warn("⚠️ [WebRtcGateway] STUN ({}:{}) NAT discovery error: {}", host, port, e.getMessage());
        }
        return null;
    }

    /**
     * Executes the WebRTC ICE connectivity and DTLS/SRTP handshake pipeline.
     */
    public void startOutboundMedia(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session == null || !session.running.get()) return;

        session.state = CallMediaState.ICE_CHECKING;
        log.info("▶️ [WebRtcGateway] ICE_CHECK_STARTED: role={} remote={} callId={}",
                session.iceControlling ? "CONTROLLING" : "CONTROLLED", session.remoteAddress, callId);

        // Aggressive ICE nomination toward Meta ice-lite: controlling + USE-CANDIDATE
        startIceConnectivityChecks(session);

        CompletableFuture.runAsync(() -> {
            try {
                session.failureReason.set(null);
                if (session.remoteAddress == null) {
                    failSession(session, "DTLS_FAILED: remoteAddress is null");
                    return;
                }

                // Give Meta a short window to answer STUN Binding before DTLS ClientHello
                waitForIceConnected(session, Math.min(2000, Math.max(500, dtlsTimeoutMs / 5)));

                boolean iceOk = session.dtlsDiagnostics.inboundStunPackets.get() > 0
                        || session.state == CallMediaState.ICE_CONNECTED;
                session.state = CallMediaState.DTLS_HANDSHAKING;
                int effectiveDtlsTimeout = Math.max(dtlsTimeoutMs, 10_000);
                log.info("🔐 [WebRtcGateway] Starting DTLS: iceOk={} inboundStun={} outboundStun={} timeoutMs={} callId={}",
                        iceOk, session.dtlsDiagnostics.inboundStunPackets.get(),
                        session.dtlsDiagnostics.outboundStunPackets.get(), effectiveDtlsTimeout, callId);

                WebRtcDtlsHandler.DtlsHandshakeResult result = dtlsHandler.startDtlsClientHandshake(
                        session.socket,
                        session.remoteAddress,
                        session.dtlsTransportAdapter,
                        session.remoteFingerprint,
                        session.localFingerprint,
                        effectiveDtlsTimeout
                );

                if (!result.isSuccess() || result.getSrtpKeyingMaterial() == null) {
                    String err = result.getErrorMessage() != null ? result.getErrorMessage() : "unknown DTLS error";
                    failSession(session, "DTLS_FAILED: " + err);
                    return;
                }

                session.dtlsTransport = result.getDtlsTransport();
                session.state = CallMediaState.DTLS_CONNECTED;
                log.info("✅ [WebRtcGateway] DTLS_CONNECTED for callId={}", callId);

                byte[] keyMaterial = result.getSrtpKeyingMaterial();
                if (keyMaterial.length < 60) {
                    failSession(session, "DTLS_FAILED: SRTP key material too short (" + keyMaterial.length + ")");
                    return;
                }
                log.info("🔑 [WebRtcGateway] SRTP_KEY_MATERIAL_DERIVED: {} bytes for callId={}", keyMaterial.length, callId);
                session.dtlsDiagnostics.srtpKeyInitResult.set("SRTP_KEY_MATERIAL_DERIVED");

                byte[] clientMasterKey = Arrays.copyOfRange(keyMaterial, 0, 16);
                byte[] serverMasterKey = Arrays.copyOfRange(keyMaterial, 16, 32);
                byte[] clientMasterSalt = Arrays.copyOfRange(keyMaterial, 32, 46);
                byte[] serverMasterSalt = Arrays.copyOfRange(keyMaterial, 46, 60);

                session.senderSrtpTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, true);
                session.receiverSrtpTransformer = new SrtpTransformer(serverMasterKey, serverMasterSalt, false);
                session.dtlsDiagnostics.srtpKeyInitResult.set("SRTP_CONTEXT_INITIALIZED");
                log.info("🔑 [WebRtcGateway] SRTP_CONTEXT_INITIALIZED for callId={}", callId);

                session.selectedSrtpProfile = result.getSelectedSrtpProfile();
                session.state = CallMediaState.SRTP_READY;
                session.dtlsDiagnostics.srtpKeyInitResult.set("SRTP_READY");
                log.info("✅ [WebRtcGateway] SRTP_READY for callId={} profile=0x{} — RTP may now be sent",
                        callId, Integer.toHexString(session.selectedSrtpProfile));
                logSessionDiagnostics(session);

                // Only after SRTP_READY
                startMediaStreamingTasks(session);
                session.state = CallMediaState.MEDIA_ACTIVE;
                log.info("🚀 [WebRtcGateway] MEDIA_ACTIVE for callId={} (SRTP_READY confirmed)", callId);

                scheduleRtpReceiveWatchdog(session);

            } catch (Exception e) {
                failSession(session, "DTLS_FAILED: " + e.getMessage());
                log.error("❌ [WebRtcGateway] Fatal DTLS Handshake exception: {}", e.getMessage(), e);
            }
        });
    }

    private void scheduleRtpReceiveWatchdog(WebRtcMediaSession session) {
        scheduler.schedule(() -> {
            if (!session.running.get()) return;
            if (session.state == CallMediaState.MEDIA_ACTIVE
                    && session.inboundSrtpPacketsCount.get() == 0
                    && session.outboundSrtpPacketsCount.get() > 0) {
                session.failureReason.set("RTP_NOT_RECEIVED");
                log.error("❌ [WebRtcGateway] ICE_CONNECTED DTLS_CONNECTED SRTP_READY RTP_NOT_RECEIVED for callId={} outSrtp={} inSrtp={}",
                        session.callId, session.outboundSrtpPacketsCount.get(), session.inboundSrtpPacketsCount.get());
                logSessionDiagnostics(session);
            }
        }, Math.max(mediaStartTimeoutMs, 1000), TimeUnit.MILLISECONDS);
    }

    /**
     * Sends repeated controlling ICE checks with USE-CANDIDATE while ICE_CHECKING / DTLS_HANDSHAKING.
     * Meta offers a=ice-lite and will not send checks itself — we must nominate the pair.
     */
    private void startIceConnectivityChecks(WebRtcMediaSession session) {
        if (session.remoteAddress != null) {
            session.sendStunBindingPing(session.remoteAddress);
        }
        session.iceCheckTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get()) return;
                CallMediaState st = session.state;
                if (st != CallMediaState.ICE_CHECKING && st != CallMediaState.DTLS_HANDSHAKING
                        && st != CallMediaState.CREATED) {
                    return;
                }
                if (session.remoteAddress != null) {
                    session.sendStunBindingPing(session.remoteAddress);
                }
            } catch (Exception e) {
                log.debug("ICE check tick error: {}", e.getMessage());
            }
        }, 200, 500, TimeUnit.MILLISECONDS);
    }

    private void waitForIceConnected(WebRtcMediaSession session, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline && session.running.get()) {
            if (session.dtlsDiagnostics.inboundStunPackets.get() > 0
                    || session.state == CallMediaState.ICE_CONNECTED) {
                session.state = CallMediaState.ICE_CONNECTED;
                log.info("✅ [WebRtcGateway] ICE_CONNECTED (STUN response received) before DTLS for callId={}",
                        session.callId);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("⚠️ [WebRtcGateway] Proceeding to DTLS without STUN response yet (inboundStun={}, waitMs={}) callId={}",
                session.dtlsDiagnostics.inboundStunPackets.get(), waitMs, session.callId);
    }

    private void failSession(WebRtcMediaSession session, String reason) {
        session.state = CallMediaState.FAILED;
        session.failureReason.set(reason);
        log.error("❌ [WebRtcGateway] {} for callId={}", reason, session.callId);
        logSessionDiagnostics(session);
        terminateSession(session.callId);
    }

    private void startMediaStreamingTasks(WebRtcMediaSession session) {
        String callId = session.callId;

        session.outboundTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Do NOT send RTP before SRTP_READY / MEDIA_ACTIVE
                if (!session.running.get() || session.remoteAddress == null) return;
                if (session.state != CallMediaState.MEDIA_ACTIVE && session.state != CallMediaState.SRTP_READY) return;
                if (session.senderSrtpTransformer == null) {
                    log.warn("⚠️ [WebRtcGateway] Refusing plaintext RTP — SRTP transformer not ready");
                    return;
                }

                byte[] plainRtp = session.buildNextRtpPacket();
                byte[] srtpPacket = session.senderSrtpTransformer.encryptRtp(plainRtp);

                DatagramPacket packet = new DatagramPacket(srtpPacket, srtpPacket.length, session.remoteAddress);
                session.socket.send(packet);
                session.outboundSrtpPacketsCount.incrementAndGet();

                if (session.sequenceNumber.get() % 50 == 0) {
                    session.sendStunBindingPing(session.remoteAddress);
                }
            } catch (Exception e) {
                // Socket closed or network glitch
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        session.vadTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get() || session.state != CallMediaState.MEDIA_ACTIVE || session.isProcessingTurn.get()) return;
                long silenceDuration = System.currentTimeMillis() - session.lastInboundPacketTime;
                int bufferSize = session.inboundPcmBuffer.size();

                if (bufferSize > 3000 && silenceDuration >= 700 && session.lastInboundPacketTime > 0) {
                    byte[] userAudio;
                    synchronized (session.inboundPcmBuffer) {
                        userAudio = session.inboundPcmBuffer.toByteArray();
                        session.inboundPcmBuffer.reset();
                    }
                    session.isProcessingTurn.set(true);
                    log.info("🗣️ [WebRtcGateway] User utterance captured ({} bytes), processing AI turn for callId={}", userAudio.length, callId);

                    CompletableFuture.runAsync(() -> {
                        try {
                            WhatsAppVoiceCallBridgeService.WhatsAppVoiceTurnResult turn =
                                    voiceCallBridgeService.processCallTurn(session.tenantId, callId, userAudio, "audio/opus", null);
                            log.info("🤖 [WebRtcGateway] AI Response for callId={}: {}", callId, turn.aiResponseText());
                            if (turn.synthesizedAudio() != null && turn.synthesizedAudio().length > 0) {
                                enqueueOutboundAudio(callId, turn.synthesizedAudio());
                            }
                        } catch (Exception e) {
                            log.error("❌ [WebRtcGateway] Error processing user voice turn: {}", e.getMessage());
                        } finally {
                            session.isProcessingTurn.set(false);
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] Error in VAD monitor: {}", e.getMessage());
            }
        }, 1000, 250, TimeUnit.MILLISECONDS);

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(800);
                if (session.state != CallMediaState.MEDIA_ACTIVE && session.state != CallMediaState.SRTP_READY) return;
                WhatsAppVoiceCallBridgeService.WhatsAppVoiceTurnResult turn =
                        voiceCallBridgeService.processCallTurn(session.tenantId, callId, new byte[0], "audio/wav", "Hello, I just connected");
                log.info("🤖 [WebRtcGateway] Welcome AI Greeting for callId={}: {}", callId, turn.aiResponseText());
                if (turn.synthesizedAudio() != null && turn.synthesizedAudio().length > 0) {
                    enqueueOutboundAudio(callId, turn.synthesizedAudio());
                }
            } catch (Exception e) {
                log.error("❌ [WebRtcGateway] Error generating welcome turn: {}", e.getMessage());
            }
        });
    }

    public void enqueueOutboundAudio(String callId, byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return;
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session == null || !session.running.get()) return;

        int chunkSize = 160;
        for (int i = 0; i < audioBytes.length; i += chunkSize) {
            int len = Math.min(chunkSize, audioBytes.length - i);
            byte[] chunk = Arrays.copyOfRange(audioBytes, i, i + len);
            session.outboundAudioQueue.offer(chunk);
        }
        log.info("🔊 [WebRtcGateway] Enqueued {} audio frames for callId={}", (audioBytes.length + chunkSize - 1) / chunkSize, callId);
    }

    public void flushOutboundAudio(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) {
            session.outboundAudioQueue.clear();
            log.info("🛑 [WebRtcGateway] Flushed outbound audio queue (barge-in) for callId={}", callId);
        }
    }

    /**
     * Returns a precise media-plane failure reason for Meta error remapping.
     * Prefer DTLS/RTP diagnoses over generic MEDIA_RECEIVE_TIMEOUT.
     */
    public String resolveMediaFailureReason(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) {
            return deriveFailureReason(session);
        }
        return lastFailureReasons.get(callId);
    }

    public CallMediaState getMediaState(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) return session.state;
        return lastMediaStates.get(callId);
    }

    private String deriveFailureReason(WebRtcMediaSession session) {
        if (session.failureReason.get() != null) {
            return session.failureReason.get();
        }
        CallMediaState state = session.state;
        if (state == CallMediaState.DTLS_HANDSHAKING || state == CallMediaState.ICE_CHECKING || state == CallMediaState.CREATED) {
            String dtlsErr = session.dtlsDiagnostics.dtlsException.get();
            return "DTLS_FAILED: handshake incomplete (state=" + state
                    + ", finished=" + session.dtlsDiagnostics.dtlsHandshakeFinished.get()
                    + ", status=" + session.dtlsDiagnostics.dtlsHandshakeStatus.get()
                    + ", engine=" + session.dtlsDiagnostics.dtlsEngineStatus.get()
                    + ", outDtls=" + session.dtlsDiagnostics.outboundDtlsPackets.get()
                    + ", inDtls=" + session.dtlsDiagnostics.inboundDtlsPackets.get()
                    + (dtlsErr != null ? ", err=" + dtlsErr : "") + ")";
        }
        if (!session.dtlsDiagnostics.dtlsHandshakeFinished.get()) {
            String dtlsErr = session.dtlsDiagnostics.dtlsException.get();
            return "DTLS_FAILED: " + (dtlsErr != null ? dtlsErr : "handshake never reached FINISHED");
        }
        if (state == CallMediaState.DTLS_CONNECTED || state == CallMediaState.SRTP_READY || state == CallMediaState.MEDIA_ACTIVE
                || state == CallMediaState.FAILED || state == CallMediaState.TERMINATED) {
            if (session.dtlsDiagnostics.dtlsHandshakeFinished.get()
                    && "SRTP_READY".equals(session.dtlsDiagnostics.srtpKeyInitResult.get())
                    && session.inboundSrtpPacketsCount.get() == 0) {
                return "RTP_NOT_RECEIVED";
            }
        }
        return null;
    }

    public void terminateSession(String callId) {
        WebRtcMediaSession session = activeSessions.remove(callId);
        if (session != null) {
            String reason = deriveFailureReason(session);
            if (reason != null) {
                session.failureReason.compareAndSet(null, reason);
                lastFailureReasons.put(callId, session.failureReason.get());
            }
            lastMediaStates.put(callId, session.state);
            session.running.set(false);
            if (session.state != CallMediaState.FAILED) {
                session.state = CallMediaState.TERMINATED;
            }
            if (session.outboundTask != null) {
                session.outboundTask.cancel(true);
            }
            if (session.vadTask != null) {
                session.vadTask.cancel(true);
            }
            if (session.iceCheckTask != null) {
                session.iceCheckTask.cancel(true);
            }
            if (session.dtlsTransportAdapter != null) {
                session.dtlsTransportAdapter.close();
            }
            if (session.socket != null && !session.socket.isClosed()) {
                session.socket.close();
            }
            logSessionDiagnostics(session);
        }
    }

    private void logSessionDiagnostics(WebRtcMediaSession session) {
        WebRtcDtlsHandler.DtlsDiagnostics d = session.dtlsDiagnostics;
        String srtpProfile = d.selectedSrtpProfileName.get() != null
                ? d.selectedSrtpProfileName.get()
                : "SRTP_AES128_CM_HMAC_SHA1_80 (0x0001)";
        log.info("\n=== WebRTC SESSION DIAGNOSTICS ===\n" +
                 "callId: {}\n" +
                 "state: {}\n" +
                 "failureReason: {}\n" +
                 "remoteCandidate: {}\n" +
                 "dtlsRole: CLIENT (a=setup:active)\n" +
                 "advertisedLocalFingerprint: {}\n" +
                 "actualCertificateFingerprint: {}\n" +
                 "remoteFingerprint: {}\n" +
                 "remoteFingerprintActual: {}\n" +
                 "certificateKeyAlgorithm: {}\n" +
                 "signatureAlgorithm: {}\n" +
                 "certificateNotAfter: {}\n" +
                 "srtpProfile: {}\n" +
                 "srtpKeyInitResult: {}\n" +
                 "dtlsHandshakeStatus: {}\n" +
                 "dtlsEngineStatus: {}\n" +
                 "dtlsHandshakeFinished: {}\n" +
                 "dtlsException: {}\n" +
                 "dtlsAlertReceived: {} ({})\n" +
                 "dtlsAlertSent: {} ({})\n" +
                 "delegatedTasksExecuted: {}\n" +
                 "dtlsWrapCalls: {}\n" +
                 "dtlsUnwrapCalls: {}\n" +
                 "inboundStunPackets: {}\n" +
                 "outboundStunPackets: {}\n" +
                 "inboundDtlsPackets: {}\n" +
                 "outboundDtlsPackets: {}\n" +
                 "inboundSrtpPackets: {}\n" +
                 "outboundSrtpPackets: {}\n" +
                 "==================================",
                session.callId, session.state, session.failureReason.get(), session.remoteAddress,
                d.advertisedLocalFingerprint.get(), d.actualCertificateFingerprint.get(),
                session.remoteFingerprint, d.remoteFingerprintActual.get(),
                d.certificateKeyAlgorithm.get(), d.signatureAlgorithm.get(), d.certificateNotAfter.get(),
                srtpProfile, d.srtpKeyInitResult.get(),
                d.dtlsHandshakeStatus.get(), d.dtlsEngineStatus.get(), d.dtlsHandshakeFinished.get(),
                d.dtlsException.get(),
                d.dtlsAlertReceived.get(), d.lastAlertReceived.get(),
                d.dtlsAlertSent.get(), d.lastAlertSent.get(),
                d.delegatedTasksExecuted.get(), d.dtlsWrapCalls.get(), d.dtlsUnwrapCalls.get(),
                d.inboundStunPackets.get(), d.outboundStunPackets.get(),
                d.inboundDtlsPackets.get(), d.outboundDtlsPackets.get(),
                session.inboundSrtpPacketsCount.get(), session.outboundSrtpPacketsCount.get());
    }

    private void startInboundListener(WebRtcMediaSession session) {
        Thread listenerThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            log.info("👂 [WebRtcGateway] Listening for UDP/RTP packets on port {}", session.socket.getLocalPort());

            while (session.running.get() && !session.socket.isClosed()) {
                try {
                    session.socket.receive(packet);
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();

                    if (session.remoteAddress == null || !session.remoteAddress.equals(sender)) {
                        session.remoteAddress = sender;
                        if (session.dtlsTransportAdapter != null) {
                            session.dtlsTransportAdapter.setRemoteAddress(sender);
                        }
                    }

                    // STUN: Binding Request=0x0001, Binding Success Response=0x0101
                    if (data.length >= 20 && data[0] == 0x00 && data[1] == 0x01) {
                        session.dtlsDiagnostics.inboundStunPackets.incrementAndGet();
                        if (session.state == CallMediaState.ICE_CHECKING || session.state == CallMediaState.CREATED) {
                            session.state = CallMediaState.ICE_CONNECTED;
                        }
                        handleStunBindingRequest(session, data, sender);
                        continue;
                    }
                    if (data.length >= 20 && data[0] == 0x01 && data[1] == 0x01) {
                        session.dtlsDiagnostics.inboundStunPackets.incrementAndGet();
                        if (session.state == CallMediaState.ICE_CHECKING || session.state == CallMediaState.CREATED) {
                            session.state = CallMediaState.ICE_CONNECTED;
                        }
                        log.info("✅ [WebRtcGateway] STUN Binding Success Response from {} (ICE path confirmed)", sender);
                        continue;
                    }

                    int firstByte = data[0] & 0xFF;
                    if (firstByte >= 20 && firstByte <= 63) {
                        session.dtlsDiagnostics.inboundDtlsPackets.incrementAndGet();
                        if (session.dtlsTransportAdapter != null) {
                            session.dtlsTransportAdapter.enqueueInbound(data);
                        }
                        continue;
                    }

                    if (data.length > 12 && ((data[0] & 0xC0) == 0x80)) {
                        session.inboundSrtpPacketsCount.incrementAndGet();
                        session.lastInboundPacketTime = System.currentTimeMillis();

                        if (session.receiverSrtpTransformer == null) {
                            // Do not treat as media before SRTP_READY
                            continue;
                        }

                        byte[] plainRtp = data;
                        byte[] decrypted = session.receiverSrtpTransformer.decryptSrtp(data);
                        if (decrypted != null) {
                            plainRtp = decrypted;
                        }

                        if (!session.outboundAudioQueue.isEmpty()) {
                            flushOutboundAudio(session.callId);
                        }

                        if (plainRtp.length > 12) {
                            byte[] payload = Arrays.copyOfRange(plainRtp, 12, plainRtp.length);
                            synchronized (session.inboundPcmBuffer) {
                                session.inboundPcmBuffer.write(payload);
                            }
                        }
                    }

                } catch (Exception e) {
                    if (!session.running.get()) break;
                }
            }
        });
        listenerThread.setName("webrtc-media-" + session.callId.substring(0, Math.min(10, session.callId.length())));
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void handleStunBindingRequest(WebRtcMediaSession session, byte[] stunRequest, InetSocketAddress sender) {
        try {
            byte[] transactionId = Arrays.copyOfRange(stunRequest, 8, 20);

            ByteArrayOutputStream attrStream = new ByteArrayOutputStream();

            attrStream.write(0x00); attrStream.write(0x20);
            attrStream.write(0x00); attrStream.write(0x08);
            attrStream.write(0x00);
            attrStream.write(0x01);
            int xorPort = sender.getPort() ^ 0x2112;
            attrStream.write((xorPort >> 8) & 0xFF);
            attrStream.write(xorPort & 0xFF);
            byte[] ipBytes = sender.getAddress().getAddress();
            byte[] magic = new byte[]{(byte) 0x21, (byte) 0x12, (byte) 0xA4, (byte) 0x42};
            for (int i = 0; i < 4; i++) {
                attrStream.write(((ipBytes[i] & 0xFF) ^ (magic[i] & 0xFF)) & 0xFF);
            }

            byte[] rawAttrs = attrStream.toByteArray();
            int totalAttrLen = rawAttrs.length + (session.pwd != null ? 32 : 0);

            ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrLen);
            buf.putShort((short) 0x0101);
            buf.putShort((short) totalAttrLen);
            buf.putInt(0x2112A442);
            buf.put(transactionId);
            buf.put(rawAttrs);

            if (session.pwd != null && !session.pwd.isBlank()) {
                byte[] forHmac = buf.array();
                int hmacLength = rawAttrs.length + 24;
                forHmac[2] = (byte) ((hmacLength >> 8) & 0xFF);
                forHmac[3] = (byte) (hmacLength & 0xFF);

                byte[] hmacKey = session.pwd.getBytes(StandardCharsets.UTF_8);
                byte[] integrity = hmacSha1(Arrays.copyOfRange(forHmac, 0, 20 + rawAttrs.length), hmacKey);

                buf.position(20 + rawAttrs.length);
                buf.putShort((short) 0x0008);
                buf.putShort((short) 20);
                buf.put(integrity);

                forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                forHmac[3] = (byte) (totalAttrLen & 0xFF);

                int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                buf.putShort((short) 0x8028);
                buf.putShort((short) 4);
                buf.putInt(crc);
            }

            byte[] responseBytes = buf.array();
            DatagramPacket respPacket = new DatagramPacket(responseBytes, responseBytes.length, sender);
            session.socket.send(respPacket);
            session.dtlsDiagnostics.outboundStunPackets.incrementAndGet();
            log.info("✅ [WebRtcGateway] Sent STUN Binding Success Response with XOR-MAPPED-ADDRESS & Integrity to {}", sender);
        } catch (Exception e) {
            log.warn("⚠️ [WebRtcGateway] Error responding to STUN request: {}", e.getMessage());
        }
    }

    private static byte[] hmacSha1(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA1 for STUN: " + e.getMessage(), e);
        }
    }

    private static int computeStunFingerprint(byte[] data, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, length);
        return ((int) crc.getValue()) ^ 0x5354554E;
    }

    private DatagramSocket allocateSocket() {
        for (int p = portRangeStart; p < portRangeStart + 500; p++) {
            try {
                return new DatagramSocket(p);
            } catch (Exception ignored) {
            }
        }
        try {
            return new DatagramSocket();
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate UDP DatagramSocket for WebRTC: " + e.getMessage(), e);
        }
    }

    private InetSocketAddress parseRemoteMetaCandidate(String sdpOffer) {
        if (sdpOffer == null) return null;
        Matcher m = Pattern.compile("(?m)^a=candidate:\\S+\\s+\\d+\\s+udp\\s+\\d+\\s+(\\S+)\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(sdpOffer);
        if (m.find()) {
            try {
                String ip = m.group(1);
                int port = Integer.parseInt(m.group(2));
                return new InetSocketAddress(InetAddress.getByName(ip), port);
            } catch (Exception ignored) {
            }
        }
        Matcher cMatcher = Pattern.compile("(?m)^c=IN IP4 (\\S+)").matcher(sdpOffer);
        Matcher mMatcher = Pattern.compile("(?m)^m=audio (\\d+)").matcher(sdpOffer);
        if (cMatcher.find() && mMatcher.find()) {
            try {
                return new InetSocketAddress(InetAddress.getByName(cMatcher.group(1)), Integer.parseInt(mMatcher.group(1)));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String parseAttribute(String sdp, String regex, String defaultVal) {
        if (sdp == null) return defaultVal;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(sdp);
        return m.find() ? m.group(1).trim() : defaultVal;
    }

    public static class WebRtcMediaSession {
        final String callId;
        final UUID tenantId;
        final String fromWaId;
        final DatagramSocket socket;
        volatile InetSocketAddress remoteAddress;
        final int payloadType;
        final String ufrag;
        final String pwd;
        final String remoteUfrag;
        final String remotePwd;
        final String remoteFingerprint;
        final String localFingerprint;
        final boolean iceControlling;
        final WebRtcDtlsHandler.DtlsDiagnostics dtlsDiagnostics;
        final WebRtcDtlsHandler.UdpDatagramTransport dtlsTransportAdapter;
        volatile org.bouncycastle.tls.DTLSTransport dtlsTransport;
        volatile SrtpTransformer senderSrtpTransformer;
        volatile SrtpTransformer receiverSrtpTransformer;
        volatile CallMediaState state = CallMediaState.CREATED;
        volatile String serializedSdpAnswer;
        volatile int selectedSrtpProfile = org.bouncycastle.tls.SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80;
        final AtomicReference<String> failureReason = new AtomicReference<>(null);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger sequenceNumber = new AtomicInteger(1000);
        final AtomicLong timestamp = new AtomicLong(0);
        final AtomicLong inboundSrtpPacketsCount = new AtomicLong(0);
        final AtomicLong outboundSrtpPacketsCount = new AtomicLong(0);
        volatile long lastInboundPacketTime = 0;
        final long ssrc = 850231558L;
        final ConcurrentLinkedQueue<byte[]> outboundAudioQueue = new ConcurrentLinkedQueue<>();
        final ByteArrayOutputStream inboundPcmBuffer = new ByteArrayOutputStream();
        final AtomicBoolean isProcessingTurn = new AtomicBoolean(false);
        ScheduledFuture<?> outboundTask;
        ScheduledFuture<?> vadTask;
        ScheduledFuture<?> iceCheckTask;

        WebRtcMediaSession(String callId, UUID tenantId, String fromWaId, DatagramSocket socket, InetSocketAddress remoteAddress, int payloadType, String ufrag, String pwd, String remoteUfrag, String remotePwd, String remoteFingerprint, String localFingerprint, boolean iceControlling) {
            this.callId = callId;
            this.tenantId = tenantId;
            this.fromWaId = fromWaId;
            this.socket = socket;
            this.remoteAddress = remoteAddress;
            this.payloadType = payloadType;
            this.ufrag = ufrag;
            this.pwd = pwd;
            this.remoteUfrag = remoteUfrag;
            this.remotePwd = remotePwd;
            this.remoteFingerprint = remoteFingerprint;
            this.localFingerprint = localFingerprint;
            this.iceControlling = iceControlling;
            this.dtlsDiagnostics = new WebRtcDtlsHandler.DtlsDiagnostics();
            this.dtlsTransportAdapter = new WebRtcDtlsHandler.UdpDatagramTransport(socket, remoteAddress, dtlsDiagnostics);
        }

        public byte[] buildNextRtpPacket() {
            byte[] payload = outboundAudioQueue.poll();
            if (payload == null) {
                payload = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
            }

            ByteBuffer buf = ByteBuffer.allocate(12 + payload.length);
            buf.put((byte) 0x80);
            buf.put((byte) (payloadType & 0x7F));
            buf.putShort((short) sequenceNumber.getAndIncrement());
            buf.putInt((int) timestamp.getAndAdd(960));
            buf.putInt((int) ssrc);
            buf.put(payload);
            return buf.array();
        }

        public byte[] buildStunBindingRequest() {
            try {
                byte[] txId = new byte[12];
                java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);

                ByteArrayOutputStream attrStream = new ByteArrayOutputStream();

                if (remoteUfrag != null && !remoteUfrag.isBlank()) {
                    String username = remoteUfrag + ":" + ufrag;
                    byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
                    attrStream.write(0x00); attrStream.write(0x06);
                    attrStream.write((userBytes.length >> 8) & 0xFF);
                    attrStream.write(userBytes.length & 0xFF);
                    attrStream.write(userBytes);
                    int pad = (4 - (userBytes.length % 4)) % 4;
                    for (int i = 0; i < pad; i++) attrStream.write(0);
                }

                attrStream.write(0x00); attrStream.write(0x24);
                attrStream.write(0x00); attrStream.write(0x04);
                attrStream.write(0x6E); attrStream.write(0x7F);
                attrStream.write(0x00); attrStream.write((byte) 0xFF);

                attrStream.write(0x00); attrStream.write(0x25);
                attrStream.write(0x00); attrStream.write(0x00);

                // ICE-CONTROLLING (0x802A) when facing Meta ice-lite; else ICE-CONTROLLED (0x8029)
                if (iceControlling) {
                    attrStream.write((byte) 0x80); attrStream.write(0x2A); // ICE-CONTROLLING
                } else {
                    attrStream.write((byte) 0x80); attrStream.write(0x29); // ICE-CONTROLLED
                }
                attrStream.write(0x00); attrStream.write(0x08);
                byte[] tieBreaker = new byte[8];
                java.util.concurrent.ThreadLocalRandom.current().nextBytes(tieBreaker);
                attrStream.write(tieBreaker);

                byte[] rawAttrs = attrStream.toByteArray();
                int totalAttrLen = rawAttrs.length + (remotePwd != null && !remotePwd.isBlank() ? 32 : 0);
                ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrLen);

                buf.putShort((short) 0x0001);
                buf.putShort((short) totalAttrLen);
                buf.putInt(0x2112A442);
                buf.put(txId);
                buf.put(rawAttrs);

                if (remotePwd != null && !remotePwd.isBlank()) {
                    byte[] forHmac = buf.array();
                    int hmacLength = rawAttrs.length + 24;
                    forHmac[2] = (byte) ((hmacLength >> 8) & 0xFF);
                    forHmac[3] = (byte) (hmacLength & 0xFF);

                    byte[] hmacKey = remotePwd.getBytes(StandardCharsets.UTF_8);
                    byte[] integrity = hmacSha1(Arrays.copyOfRange(forHmac, 0, 20 + rawAttrs.length), hmacKey);

                    buf.position(20 + rawAttrs.length);
                    buf.putShort((short) 0x0008);
                    buf.putShort((short) 20);
                    buf.put(integrity);

                    forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                    forHmac[3] = (byte) (totalAttrLen & 0xFF);

                    int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                    buf.putShort((short) 0x8028);
                    buf.putShort((short) 4);
                    buf.putInt(crc);
                }

                return buf.array();
            } catch (Exception e) {
                log.warn("Error building STUN binding request: {}", e.getMessage());
                return new byte[0];
            }
        }

        public void sendStunBindingPing(InetSocketAddress destination) {
            if (destination == null || socket.isClosed()) return;
            try {
                byte[] packetData = buildStunBindingRequest();
                if (packetData.length > 0) {
                    DatagramPacket packet = new DatagramPacket(packetData, packetData.length, destination);
                    socket.send(packet);
                    dtlsDiagnostics.outboundStunPackets.incrementAndGet();
                    log.info("📡 [WebRtcGateway] STUN_REQUEST_SENT: role={} USE-CANDIDATE to Meta at {}",
                            iceControlling ? "CONTROLLING" : "CONTROLLED", destination);
                }
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] Failed to send STUN binding ping: {}", e.getMessage());
            }
        }
    }
}
