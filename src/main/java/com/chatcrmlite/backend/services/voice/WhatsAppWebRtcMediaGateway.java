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
        NEW,
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

    private volatile String resolvedPublicIp = null;

    private final Map<String, WebRtcMediaSession> activeSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    /**
     * Resolves the server's public IP address for WebRTC NAT traversal
     */
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

        // Terminate any existing session for this callId
        terminateSession(callId);

        // 1. Allocate UDP DatagramSocket
        DatagramSocket socket = allocateSocket();
        int localPort = socket.getLocalPort();

        // 2. STUN NAT Hole-Punching via STUN server
        InetSocketAddress srflxCandidate = discoverSrflxCandidate(socket);
        String effectiveIp = (srflxCandidate != null) ? srflxCandidate.getHostString() : getPublicMediaIp();

        // 3. Parse Remote Meta Candidate and SDP details
        InetSocketAddress remoteMetaAddress = parseRemoteMetaCandidate(sdpOffer);
        log.info("🎯 [WebRtcGateway] Target Meta Remote Candidate: {}", remoteMetaAddress);

        String mid = parseAttribute(sdpOffer, "(?m)^a=mid:(\\S+)", "audio");
        String opusPt = parseAttribute(sdpOffer, "(?m)^a=rtpmap:(\\d+)\\s+opus/48000/2", "111");
        String remoteUfrag = parseAttribute(sdpOffer, "(?m)^a=ice-ufrag:(\\S+)", null);
        String remotePwd = parseAttribute(sdpOffer, "(?m)^a=ice-pwd:(\\S+)", null);

        long sessionId = Math.abs((long) callId.hashCode() + 1000000000L);
        String ufrag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String pwd = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String fingerprint = dtlsHandler.getSha256Fingerprint();

        WebRtcMediaSession session = new WebRtcMediaSession(
                callId, tenantId, fromWaId, socket, remoteMetaAddress, Integer.parseInt(opusPt), ufrag, pwd, remoteUfrag, remotePwd
        );
        activeSessions.put(callId, session);

        // 4. Start Inbound UDP Listener & STUN Handler
        startInboundListener(session);

        // 5. Generate RFC 8866 compliant SDP answer tailored for Meta WhatsApp WebRTC Engine
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
        sdp.append("a=fingerprint:SHA-256 ").append(fingerprint).append("\r\n");
        sdp.append("a=setup:active\r\n"); // We act as DTLS client (initiator)
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

        return sdp.toString();
    }

    /**
     * Discovers external NAT mapped candidate using STUN
     */
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
            req.putShort((short) 0x0001); // STUN Binding Request
            req.putShort((short) 0x0000); // 0 attributes
            req.putInt(0x2112A442);       // Magic Cookie
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
        log.info("▶️ [WebRtcGateway] Starting ICE check and DTLS handshake for callId={}", callId);

        // Send active STUN binding ping to Meta's remote candidate
        if (session.remoteAddress != null) {
            session.sendStunBindingPing(session.remoteAddress);
        }

        // Trigger DTLS 1.2 Handshake asynchronously with Meta endpoint
        CompletableFuture.runAsync(() -> {
            try {
                session.state = CallMediaState.DTLS_HANDSHAKING;
                if (session.remoteAddress != null) {
                    WebRtcDtlsHandler.DtlsHandshakeResult result = dtlsHandler.startDtlsClientHandshake(
                            session.socket, session.remoteAddress, session.dtlsTransportAdapter
                    );

                    if (result.isSuccess() && result.getSrtpKeyingMaterial() != null) {
                        session.dtlsTransport = result.getDtlsTransport();
                        session.state = CallMediaState.DTLS_CONNECTED;

                        // Derive SRTP keys (RFC 5764 & RFC 3711)
                        byte[] keyMaterial = result.getSrtpKeyingMaterial();
                        byte[] clientMasterKey = Arrays.copyOfRange(keyMaterial, 0, 16);
                        byte[] serverMasterKey = Arrays.copyOfRange(keyMaterial, 16, 32);
                        byte[] clientMasterSalt = Arrays.copyOfRange(keyMaterial, 32, 46);
                        byte[] serverMasterSalt = Arrays.copyOfRange(keyMaterial, 46, 60);

                        // As DTLS client: encrypt outbound with client key/salt, decrypt inbound with server key/salt
                        session.senderSrtpTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, true);
                        session.receiverSrtpTransformer = new SrtpTransformer(serverMasterKey, serverMasterSalt, false);

                        session.state = CallMediaState.SRTP_READY;
                        session.state = CallMediaState.MEDIA_ACTIVE;
                        log.info("🚀 [WebRtcGateway] Call callId={} transition to MEDIA_ACTIVE (SRTP Encryption & Decryption Ready)", callId);

                        // Start active media streaming and VAD tasks
                        startMediaStreamingTasks(session);

                    } else {
                        session.state = CallMediaState.FAILED;
                        log.error("❌ [WebRtcGateway] DTLS Handshake FAILED for callId={}: {}", callId, result.getErrorMessage());
                        terminateSession(callId);
                    }
                }
            } catch (Exception e) {
                session.state = CallMediaState.FAILED;
                log.error("❌ [WebRtcGateway] Fatal DTLS Handshake exception: {}", e.getMessage(), e);
                terminateSession(callId);
            }
        });
    }

    private void startMediaStreamingTasks(WebRtcMediaSession session) {
        String callId = session.callId;

        // 1. Schedule periodic SRTP comfort/audio frame transmission every 20ms
        session.outboundTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get() || session.state != CallMediaState.MEDIA_ACTIVE || session.remoteAddress == null) return;

                byte[] plainRtp = session.buildNextRtpPacket();
                byte[] srtpPacket = session.senderSrtpTransformer != null ? session.senderSrtpTransformer.encryptRtp(plainRtp) : plainRtp;

                DatagramPacket packet = new DatagramPacket(srtpPacket, srtpPacket.length, session.remoteAddress);
                session.socket.send(packet);
                session.outboundPacketsCount.incrementAndGet();

                // Periodic STUN keepalive ping every ~1s (every 50 frames)
                if (session.sequenceNumber.get() % 50 == 0) {
                    session.sendStunBindingPing(session.remoteAddress);
                }
            } catch (Exception e) {
                // Socket closed or network glitch
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        // 2. Schedule periodic VAD endpointing loop to detect user speech and trigger AI response
        session.vadTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get() || session.state != CallMediaState.MEDIA_ACTIVE || session.isProcessingTurn.get()) return;
                long silenceDuration = System.currentTimeMillis() - session.lastInboundPacketTime;
                int bufferSize = session.inboundPcmBuffer.size();

                // If user spoke (received > 3000 bytes) and has paused for >= 700ms
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
                                    voiceCallBridgeService.processCallTurn(session.tenantId, callId, userAudio, "audio/wav", null);
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

        // 3. Trigger welcome voice turn asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(800);
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

    /**
     * Enqueues synthesized audio into the WebRTC session output queue (sliced into 20ms frames)
     */
    public void enqueueOutboundAudio(String callId, byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return;
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session == null || !session.running.get()) return;

        int chunkSize = 160; // ~20ms frames
        for (int i = 0; i < audioBytes.length; i += chunkSize) {
            int len = Math.min(chunkSize, audioBytes.length - i);
            byte[] chunk = Arrays.copyOfRange(audioBytes, i, i + len);
            session.outboundAudioQueue.offer(chunk);
        }
        log.info("🔊 [WebRtcGateway] Enqueued {} audio frames for callId={}", (audioBytes.length + chunkSize - 1) / chunkSize, callId);
    }

    /**
     * Flushes queued outbound audio on caller barge-in
     */
    public void flushOutboundAudio(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) {
            session.outboundAudioQueue.clear();
            log.info("🛑 [WebRtcGateway] Flushed outbound audio queue (barge-in) for callId={}", callId);
        }
    }

    /**
     * Terminates the WebRTC media session and cleans up UDP ports.
     */
    public void terminateSession(String callId) {
        WebRtcMediaSession session = activeSessions.remove(callId);
        if (session != null) {
            session.running.set(false);
            session.state = CallMediaState.TERMINATED;
            if (session.outboundTask != null) {
                session.outboundTask.cancel(true);
            }
            if (session.vadTask != null) {
                session.vadTask.cancel(true);
            }
            if (session.dtlsTransportAdapter != null) {
                session.dtlsTransportAdapter.close();
            }
            if (session.socket != null && !session.socket.isClosed()) {
                session.socket.close();
            }
            log.info("⏹️ [WebRtcGateway] Media session closed for callId={}. Inbound packets: {}, Outbound packets: {}",
                    callId, session.inboundPacketsCount.get(), session.outboundPacketsCount.get());
        }
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

                    // Update remote address if Meta sends from a different candidate port
                    if (session.remoteAddress == null) {
                        session.remoteAddress = sender;
                    }

                    // 1. Handle STUN Binding Request (0x0001) or Response (0x0101)
                    if (data.length >= 20 && (data[0] == 0x00 || data[0] == 0x01) && data[1] == 0x01) {
                        session.state = CallMediaState.ICE_CONNECTED;
                        handleStunBindingRequest(session, data, sender);
                        continue;
                    }

                    // 2. Handle DTLS packets (ContentType: 20=ChangeCipherSpec, 21=Alert, 22=Handshake, 23=ApplicationData)
                    int firstByte = data[0] & 0xFF;
                    if (firstByte >= 20 && firstByte <= 63) {
                        if (session.dtlsTransportAdapter != null) {
                            session.dtlsTransportAdapter.enqueueInbound(data);
                        }
                        continue;
                    }

                    // 3. Handle Inbound SRTP/RTP Audio Packet
                    if (data.length > 12 && ((data[0] & 0xC0) == 0x80)) {
                        session.inboundPacketsCount.incrementAndGet();
                        session.lastInboundPacketTime = System.currentTimeMillis();

                        // Decrypt SRTP packet using receiver transformer if available
                        byte[] plainRtp = data;
                        if (session.receiverSrtpTransformer != null) {
                            byte[] decrypted = session.receiverSrtpTransformer.decryptSrtp(data);
                            if (decrypted != null) {
                                plainRtp = decrypted;
                            }
                        }

                        // Barge-in: if user speaks while AI is playing, flush outbound queue
                        if (!session.outboundAudioQueue.isEmpty()) {
                            flushOutboundAudio(session.callId);
                        }

                        // Extract RTP payload (skip 12-byte header)
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

    /**
     * Responds to Meta STUN Binding Requests with XOR-MAPPED-ADDRESS, MESSAGE-INTEGRITY, and FINGERPRINT.
     */
    private void handleStunBindingRequest(WebRtcMediaSession session, byte[] stunRequest, InetSocketAddress sender) {
        try {
            byte[] transactionId = Arrays.copyOfRange(stunRequest, 8, 20);

            ByteArrayOutputStream attrStream = new ByteArrayOutputStream();

            // 1. XOR-MAPPED-ADDRESS (0x0020)
            attrStream.write(0x00); attrStream.write(0x20);
            attrStream.write(0x00); attrStream.write(0x08);
            attrStream.write(0x00); // Reserved
            attrStream.write(0x01); // IPv4
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
            buf.putShort((short) 0x0101); // STUN Binding Success Response
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
                buf.putShort((short) 0x0008); // MESSAGE-INTEGRITY
                buf.putShort((short) 20);
                buf.put(integrity);

                forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                forHmac[3] = (byte) (totalAttrLen & 0xFF);

                int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                buf.putShort((short) 0x8028); // FINGERPRINT
                buf.putShort((short) 4);
                buf.putInt(crc);
            }

            byte[] responseBytes = buf.array();
            DatagramPacket respPacket = new DatagramPacket(responseBytes, responseBytes.length, sender);
            session.socket.send(respPacket);
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

    /**
     * Active WebRTC Call Media Session
     */
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
        final WebRtcDtlsHandler.UdpDatagramTransport dtlsTransportAdapter;
        volatile org.bouncycastle.tls.DTLSTransport dtlsTransport;
        volatile SrtpTransformer senderSrtpTransformer;
        volatile SrtpTransformer receiverSrtpTransformer;
        volatile CallMediaState state = CallMediaState.NEW;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger sequenceNumber = new AtomicInteger(1000);
        final AtomicLong timestamp = new AtomicLong(0);
        final AtomicLong inboundPacketsCount = new AtomicLong(0);
        final AtomicLong outboundPacketsCount = new AtomicLong(0);
        volatile long lastInboundPacketTime = 0;
        final long ssrc = 850231558L;
        final ConcurrentLinkedQueue<byte[]> outboundAudioQueue = new ConcurrentLinkedQueue<>();
        final ByteArrayOutputStream inboundPcmBuffer = new ByteArrayOutputStream();
        final AtomicBoolean isProcessingTurn = new AtomicBoolean(false);
        ScheduledFuture<?> outboundTask;
        ScheduledFuture<?> vadTask;

        WebRtcMediaSession(String callId, UUID tenantId, String fromWaId, DatagramSocket socket, InetSocketAddress remoteAddress, int payloadType, String ufrag, String pwd, String remoteUfrag, String remotePwd) {
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
            this.dtlsTransportAdapter = new WebRtcDtlsHandler.UdpDatagramTransport(socket, remoteAddress);
        }

        /**
         * Builds an RTP packet containing either next queued audio frame or Opus comfort silence (0xF8, 0xFF, 0xFE)
         */
        public byte[] buildNextRtpPacket() {
            byte[] payload = outboundAudioQueue.poll();
            if (payload == null) {
                payload = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE}; // Opus silence frame
            }

            ByteBuffer buf = ByteBuffer.allocate(12 + payload.length);

            // RTP Header
            buf.put((byte) 0x80);                                // Version 2, no padding, no extensions, 0 CSRC
            buf.put((byte) (payloadType & 0x7F));                // Marker=0, PayloadType (111)
            buf.putShort((short) sequenceNumber.getAndIncrement()); // Sequence Number
            buf.putInt((int) timestamp.getAndAdd(960));          // Timestamp (+960 samples for 20ms @ 48kHz)
            buf.putInt((int) ssrc);                              // SSRC
            buf.put(payload);

            return buf.array();
        }

        public byte[] buildStunBindingRequest() {
            try {
                byte[] txId = new byte[12];
                java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);

                ByteArrayOutputStream attrStream = new ByteArrayOutputStream();

                // 1. USERNAME (0x0006): <remoteUfrag>:<localUfrag>
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

                // 2. PRIORITY (0x0024): 1853824767
                attrStream.write(0x00); attrStream.write(0x24);
                attrStream.write(0x00); attrStream.write(0x04);
                attrStream.write(0x6E); attrStream.write(0x7F);
                attrStream.write(0x00); attrStream.write((byte) 0xFF);

                // 3. USE-CANDIDATE (0x0025): Length 0
                attrStream.write(0x00); attrStream.write(0x25);
                attrStream.write(0x00); attrStream.write(0x00);

                // 4. ICE-CONTROLLED (0x8029): Length 8
                attrStream.write((byte) 0x80); attrStream.write(0x29);
                attrStream.write(0x00); attrStream.write(0x08);
                byte[] tieBreaker = new byte[8];
                java.util.concurrent.ThreadLocalRandom.current().nextBytes(tieBreaker);
                attrStream.write(tieBreaker);

                byte[] rawAttrs = attrStream.toByteArray();
                int totalAttrLen = rawAttrs.length + (remotePwd != null && !remotePwd.isBlank() ? 32 : 0);
                ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrLen);

                buf.putShort((short) 0x0001); // Binding Request
                buf.putShort((short) totalAttrLen);
                buf.putInt(0x2112A442);       // Magic Cookie
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
                    buf.putShort((short) 0x0008); // MESSAGE-INTEGRITY
                    buf.putShort((short) 20);
                    buf.put(integrity);

                    forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                    forHmac[3] = (byte) (totalAttrLen & 0xFF);

                    int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                    buf.putShort((short) 0x8028); // FINGERPRINT
                    buf.putShort((short) 4);
                    buf.putInt(crc);
                }

                return buf.array();
            } catch (Exception e) {
                log.warn("Error building STUN binding request: {}", e.getMessage());
                return new byte[0];
            }
        }

        /**
         * Sends an active STUN binding request ping to the remote candidate to open NAT bindings and establish 2-way ICE path
         */
        public void sendStunBindingPing(InetSocketAddress destination) {
            if (destination == null || socket.isClosed()) return;
            try {
                byte[] packetData = buildStunBindingRequest();
                if (packetData.length > 0) {
                    DatagramPacket packet = new DatagramPacket(packetData, packetData.length, destination);
                    socket.send(packet);
                    log.info("📡 [WebRtcGateway] Sent authenticated STUN ICE binding ping to Meta remote address: {}", destination);
                }
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] Failed to send STUN binding ping: {}", e.getMessage());
            }
        }
    }
}
