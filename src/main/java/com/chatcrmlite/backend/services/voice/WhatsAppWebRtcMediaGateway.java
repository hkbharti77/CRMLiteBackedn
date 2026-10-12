package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.ai.DeepgramLiveSttSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.github.jaredmdobson.concentus.OpusDecoder;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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
import com.chatcrmlite.backend.services.voice.turn.TurnClient;
import com.chatcrmlite.backend.services.voice.turn.TurnSession;

/**
 * Enterprise Native Java WebRTC Media Gateway for WhatsApp Business Calling.
 * Handles UDP socket lifecycle, STUN Binding responses, DTLS 1.2, SRTP encryption/decryption, and AI audio streaming.
 */
@Slf4j
@Service
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
    private final TurnClient turnClient;

    @Autowired
    public WhatsAppWebRtcMediaGateway(WhatsAppVoiceCallBridgeService voiceCallBridgeService,
                                     DeepgramVoiceService deepgramVoiceService,
                                     WebRtcDtlsHandler dtlsHandler,
                                     @Autowired(required = false) TurnClient turnClient) {
        this.voiceCallBridgeService = voiceCallBridgeService;
        this.deepgramVoiceService = deepgramVoiceService;
        this.dtlsHandler = dtlsHandler;
        this.turnClient = turnClient;
    }

    public WhatsAppWebRtcMediaGateway(WhatsAppVoiceCallBridgeService voiceCallBridgeService,
                                     DeepgramVoiceService deepgramVoiceService,
                                     WebRtcDtlsHandler dtlsHandler) {
        this(voiceCallBridgeService, deepgramVoiceService, dtlsHandler, null);
    }

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

    @Value("${crmlite.calling.turn.username:}")
    private String turnUsername;

    @Value("${crmlite.calling.turn.credential:}")
    private String turnCredential;

    @Value("${crmlite.calling.timeout.dtls-ms:15000}")
    private int dtlsTimeoutMs;

    @Value("${crmlite.calling.timeout.media-start-ms:10000}")
    private int mediaStartTimeoutMs;

    private volatile String resolvedPublicIp = null;

    private final Map<String, WebRtcMediaSession> activeSessions = new ConcurrentHashMap<>();
    /** Survives terminateSession so Meta 138021 can be remapped to the real DTLS/RTP cause. */
    private final Map<String, String> lastFailureReasons = new ConcurrentHashMap<>();
    private final Map<String, CallMediaState> lastMediaStates = new ConcurrentHashMap<>();
    /** Holds finalized conversation audio bytes upon termination for asynchronous Cloudinary upload. */
    private final Map<String, byte[]> lastRecordedAudios = new ConcurrentHashMap<>();
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

    private String getLocalHostIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private String getLocalHostIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet6Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && !addr.isSiteLocalAddress()) {
                        String host = addr.getHostAddress();
                        int zoneIndex = host.indexOf('%');
                        if (zoneIndex != -1) {
                            host = host.substring(0, zoneIndex);
                        }
                        return host;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
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
        String localHostIp = getLocalHostIp();
        String localHostIpv6 = getLocalHostIpv6();

        List<InetSocketAddress> allRemoteCandidates = parseAllRemoteCandidates(sdpOffer);
        // Default target to Meta's primary IPv4 candidate (matching IPv4 TURN relay)
        InetSocketAddress remoteMetaAddress = null;
        for (InetSocketAddress c : allRemoteCandidates) {
            if (c.getAddress() instanceof Inet4Address) {
                remoteMetaAddress = c;
                break;
            }
        }
        if (remoteMetaAddress == null && !allRemoteCandidates.isEmpty()) {
            remoteMetaAddress = allRemoteCandidates.get(0);
        }
        log.info("🎯 [WebRtcGateway] Target Meta Remote Candidate: {} (total parsed candidates: {}, localIpv6={})",
                remoteMetaAddress, allRemoteCandidates.size(), localHostIpv6);

        TurnSession turnSession = null;
        if (turnEnabled && turnClient != null && relayHost != null && !relayHost.isBlank() && remoteMetaAddress != null) {
            try {
                log.info("🌐 [WebRtcGateway] Allocating TURN session for callId={} to {} via {}:{}",
                        callId, remoteMetaAddress, relayHost, relayPort);
                turnSession = turnClient.allocateSession(
                        relayHost, relayPort, turnUsername, turnCredential,
                        remoteMetaAddress, allRemoteCandidates,
                        packet -> handleInboundPacketForCall(callId, packet)
                );
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] TURN allocation failed for callId={}: {}", callId, e.getMessage());
            }
        }

        InetSocketAddress srflxCandidate = (turnSession == null) ? discoverSrflxCandidate(socket) : null;
        String mediaIp;
        int mediaPort;
        if (turnSession != null) {
            mediaIp = turnSession.getRelayedAddress().getHostString();
            mediaPort = turnSession.getRelayedAddress().getPort();
            log.info("🎯 [WebRtcGateway] Active TURN Relayed Endpoint: {}:{}", mediaIp, mediaPort);
        } else {
            mediaIp = (srflxCandidate != null) ? srflxCandidate.getHostString() : getPublicMediaIp();
            mediaPort = (srflxCandidate != null) ? srflxCandidate.getPort() : localPort;
        }

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
                callId, tenantId, fromWaId, socket, remoteMetaAddress, allRemoteCandidates, Integer.parseInt(opusPt),
                ufrag, pwd, remoteUfrag, remotePwd, remoteFingerprint, localFingerprint, iceControlling
        );
        session.turnSession = turnSession;
        if (session.dtlsTransportAdapter != null) {
            session.dtlsTransportAdapter.setPacketSender(session::sendMediaPacket);
        }
        session.state = CallMediaState.CREATED;
        activeSessions.put(callId, session);

        startInboundListener(session);

        // Start ICE connectivity checks immediately so NAT hole punching begins while pre_accept is in flight
        startIceConnectivityChecks(session);

        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- ").append(sessionId).append(" 2 IN IP4 127.0.0.1\r\n");
        sdp.append("s=CRMLite WebRTC Engine\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE ").append(mid).append("\r\n");
        sdp.append("a=msid-semantic: WMS\r\n");
        sdp.append("m=audio ").append(mediaPort).append(" UDP/TLS/RTP/SAVPF ").append(opusPt).append("\r\n");
        sdp.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        sdp.append("a=rtcp:9 IN IP4 0.0.0.0\r\n");
        sdp.append("a=ice-ufrag:").append(ufrag).append("\r\n");
        sdp.append("a=ice-pwd:").append(pwd).append("\r\n");
        sdp.append("a=fingerprint:SHA-256 ").append(localFingerprint).append("\r\n");
        sdp.append("a=setup:active\r\n");
        sdp.append("a=mid:").append(mid).append("\r\n");
        sdp.append("a=rtcp-mux\r\n");
        sdp.append("a=rtcp-rsize\r\n");
        sdp.append("a=sendrecv\r\n");
        sdp.append("a=rtpmap:").append(opusPt).append(" opus/48000/2\r\n");
        sdp.append("a=fmtp:").append(opusPt).append(" minptime=10;useinbandfec=1\r\n");
        sdp.append("a=ptime:20\r\n");
        sdp.append("a=maxptime:20\r\n");
        sdp.append("a=ssrc:").append(session.ssrc).append(" cname:CRMLiteAudioStream\r\n");

        int candIndex = 1;
        if (turnSession != null) {
            // When TURN Relay is active on mobile hotspot/CGNAT, strictly advertise ONLY the relayed candidate.
            // Advertising unreachable private IPv4 or carrier-blocked IPv6 causes Meta ICE to attempt host pairs,
            // dropping packets and starving the working TURN TCP relay pair!
            // Candidate priority matches STUN PRIORITY attribute 0x6E7F00FF (1853817087) per RFC 5245 §7.1.2.1
            sdp.append("a=candidate:1 1 udp 1853817087 ").append(mediaIp).append(" ").append(mediaPort)
               .append(" typ relay raddr ").append(localHostIp).append(" rport ").append(localPort).append("\r\n");
        } else {
            // When hosted on Cloud/VPS with a public IP (e.g. AWS EC2 13.211.146.210),
            // Meta ICE-lite REQUIRES the reachable public IP to be advertised as 'typ host'!
            // Advertising internal docker IP (172.18.0.x) or 'typ srflx' with private raddr causes Meta
            // to drop the candidate or attempt routing to the unroutable 172.18.0.x IP.
            long candPriority = 2130706431L; // 0x7EFE00FF host candidate priority
            sdp.append("a=candidate:1 1 udp ").append(candPriority).append(" ").append(mediaIp).append(" ").append(mediaPort).append(" typ host\r\n");
            if (!mediaIp.equals(localHostIp) && !isPrivateOrLoopback(localHostIp)) {
                sdp.append("a=candidate:2 1 udp 2130706429 ").append(localHostIp).append(" ").append(localPort).append(" typ host\r\n");
            }
        }

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

                // In WebRTC with ICE-Lite (Meta), proceed directly with DTLS ClientHello concurrently
                // with background STUN checks per RFC 8842 §4 and Meta Calling guidelines.
                boolean usingTurn = session.turnSession != null && session.turnSession.isActive();
                waitForIceConnected(session, 500);

                boolean iceOk = session.dtlsDiagnostics.inboundStunPackets.get() > 0
                        || session.state == CallMediaState.ICE_CONNECTED;
                session.state = CallMediaState.DTLS_HANDSHAKING;
                int effectiveDtlsTimeout = Math.max(dtlsTimeoutMs, 10_000);
                log.info("🔐 [WebRtcGateway] Starting DTLS: iceOk={} turn={} inboundStun={} outboundStun={} timeoutMs={} callId={}",
                        iceOk, usingTurn, session.dtlsDiagnostics.inboundStunPackets.get(),
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

                // RFC 5764: DTLS client writes with client_* keys, decrypts server_* keys.
                // Keep swapped pair ready — Meta auth-tag failures almost always mean key direction mismatch.
                session.senderSrtpTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, true);
                session.receiverSrtpTransformer = new SrtpTransformer(serverMasterKey, serverMasterSalt, false);
                session.altSenderSrtpTransformer = new SrtpTransformer(serverMasterKey, serverMasterSalt, true);
                session.altReceiverSrtpTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, false);
                session.srtpKeysSwapped.set(false);
                session.dtlsDiagnostics.srtpKeyInitResult.set("SRTP_CONTEXT_INITIALIZED");
                log.info("🔑 [WebRtcGateway] SRTP_CONTEXT_INITIALIZED for callId={} (client_write for TX, server_write for RX)", callId);

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
        session.watchdogTask = scheduler.schedule(() -> {
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
        if (session.iceCheckTask != null && !session.iceCheckTask.isDone()) {
            return;
        }
        sendPingsToAll(session);
        session.iceCheckTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get()) return;
                CallMediaState st = session.state;
                if (st != CallMediaState.ICE_CHECKING && st != CallMediaState.DTLS_HANDSHAKING
                        && st != CallMediaState.CREATED) {
                    return;
                }
                sendPingsToAll(session);
            } catch (Exception e) {
                log.debug("ICE check tick error: {}", e.getMessage());
            }
        }, 200, 500, TimeUnit.MILLISECONDS);
    }

    private void sendPingsToAll(WebRtcMediaSession session) {
        // TURN ChannelBind is to a single peer — only ping that peer via the relay.
        if (session.turnSession != null && session.turnSession.isActive()) {
            InetSocketAddress peer = session.turnSession.getPeerAddress();
            if (peer != null) {
                session.sendStunBindingPing(peer);
            } else if (session.remoteAddress != null) {
                session.sendStunBindingPing(session.remoteAddress);
            }
            return;
        }
        if (session.allRemoteCandidates != null && !session.allRemoteCandidates.isEmpty()) {
            for (InetSocketAddress target : session.allRemoteCandidates) {
                session.sendStunBindingPing(target);
            }
        } else if (session.remoteAddress != null) {
            session.sendStunBindingPing(session.remoteAddress);
        }
    }

    private void waitForIceConnected(WebRtcMediaSession session, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline && session.running.get()) {
            if (session.dtlsDiagnostics.inboundStunPackets.get() > 0
                    || session.state == CallMediaState.ICE_CONNECTED) {
                session.state = CallMediaState.ICE_CONNECTED;
                log.info("✅ [WebRtcGateway] ICE_CONNECTED (STUN response received) before DTLS for callId={}",
                        session.callId);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
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

        // Phase 2: Start real-time WebSocket Streaming STT session
        if (deepgramVoiceService != null && deepgramVoiceService.isStreamingSttEnabled() && session.liveSttSession == null) {
            try {
                session.liveSttSession = deepgramVoiceService.createLiveStreamingSession(callId, "en-IN");
                log.info("🎙️ [WebRtcGateway] Initialized live streaming STT session for callId={}", callId);
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] Failed to start live STT session for callId={}: {}", callId, e.getMessage());
            }
        }

        session.outboundTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Do NOT send RTP before SRTP_READY / MEDIA_ACTIVE
                if (!session.running.get() || session.remoteAddress == null) return;
                if (session.state != CallMediaState.MEDIA_ACTIVE && session.state != CallMediaState.SRTP_READY) return;
                if (session.senderSrtpTransformer == null) {
                    log.warn("⚠️ [WebRtcGateway] Refusing plaintext RTP — SRTP transformer not ready");
                    return;
                }

                // Advance the RTP timestamp by 960 (20ms @ 48kHz) EVERY tick, even if DTX skips sending.
                // This keeps the RTP clock in sync with real wall-clock time and prevents massive distortion.
                int currentTimestamp = (int) session.timestamp.getAndAdd(960);
                long startMs = System.currentTimeMillis();
                long totalTicks = session.dtxTickCount.incrementAndGet();

                WebRtcMediaSession.OutboundRtpFrame frame = session.buildNextRtpPacket(currentTimestamp);
                
                if (frame != null && frame.packetData != null) {
                    byte[] srtpPacket = session.senderSrtpTransformer.encryptRtp(frame.packetData);
                    if (srtpPacket != null) {
                        session.sendMediaPacket(srtpPacket);
                        session.outboundSrtpPacketsCount.incrementAndGet();

                        if (!frame.isSilence) {
                            long currentWallClock = System.currentTimeMillis();
                            long prevWallClock = session.lastRtpSendWallClockMs.getAndSet(currentWallClock);
                            long delta = prevWallClock == 0 ? 0 : currentWallClock - prevWallClock;
                            int currentSeq = session.sequenceNumber.get() - 1;
                            log.info("📊 [RTP-METRIC] callId={} turnId={} seq={} ts={} deltaWallClock={}ms queueDepth={} bytes={}",
                                    callId, session.currentTtsTurnId, currentSeq, currentTimestamp, delta, session.outboundAudioQueue.size(), frame.packetData.length);

                            if (session.firstRtpSentMs.get() == 0 && session.userUtteranceEndMs.get() > 0) {
                                long firstRtpTime = System.currentTimeMillis();
                                session.firstRtpSentMs.set(firstRtpTime);
                                long totalE2e = firstRtpTime - session.userUtteranceEndMs.get();
                                log.info("📈 [LATENCY-METRIC] Metric D: TTS first audio to first RTP sent (usually 0ms).");
                                log.info("📈 [LATENCY-METRIC] Metric E: User stopped speaking -> First RTP sent: {}ms", totalE2e);
                            }
                            session.lastRtpSendTime = startMs;
                        }
                    }
                }

                // Send STUN ping every 50 ticks (1 second) to keep ICE alive
                if (totalTicks % 50 == 0) {
                    session.sendStunBindingPing(session.remoteAddress);
                    if (frame != null && !frame.isSilence) {
                        log.info("[RTP-OUT] seq={} timestamp={} wallClock={} queueDepth={} deltaMs={}",
                                session.sequenceNumber.get(), currentTimestamp, startMs, session.outboundAudioQueue.size(), (System.currentTimeMillis() - session.lastRtpSendTime));
                    }
                }
            } catch (Exception e) {
                // Socket closed or network glitch
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        session.vadTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.running.get() || session.state != CallMediaState.MEDIA_ACTIVE || session.isProcessingTurn.get()) return;
                
                int bufferSize = session.inboundPcmBuffer.size();
                long now = System.currentTimeMillis();
                long silenceDuration = now - session.lastVoiceActivityTime;
                long speechDuration = now - session.speechStartTime;

                if (!session.hasSpokenInTurn) {
                    // Prevent memory bloat from continuous silence packets before user speaks (keep max 400ms pre-roll)
                    if (bufferSize > 38400) {
                        synchronized (session.inboundPcmBuffer) {
                            byte[] current = session.inboundPcmBuffer.toByteArray();
                            session.inboundPcmBuffer.reset();
                            int keepOffset = current.length - 38400;
                            if (keepOffset > 0) {
                                session.inboundPcmBuffer.write(current, keepOffset, 38400);
                            }
                        }
                    }
                    return;
                }

                // Utterance completion trigger:
                // 1) User finished speaking (silenceDuration >= 650ms after speech)
                // 2) Live STT neural endpointing (speech_final detected + silence >= 350ms)
                // 3) Max speech safety cap (speechDuration >= 5000ms): Never allow speech to buffer beyond 5s to eliminate 45s STT delays!
                boolean liveSttEndpointed = (session.liveSttSession != null && session.liveSttSession.hasSpeechFinal() && silenceDuration >= 350);
                if ((silenceDuration >= 650 && session.lastVoiceActivityTime > 0) || liveSttEndpointed || speechDuration >= 5000) {
                    byte[] userAudio;
                    synchronized (session.inboundPcmBuffer) {
                        userAudio = session.inboundPcmBuffer.toByteArray();
                        session.inboundPcmBuffer.reset();
                    }
                    session.hasSpokenInTurn = false;
                    int voicedFrames = session.voicedFramesInUtterance.getAndSet(0);

                    // Filter out transient room noises (door slam, cough, mic tap, short breath)
                    // Minimum 6 voiced frames (~120ms of actual voiced audio) and 150ms total audio preserves short words ("Yes", "No", "Hi")
                    if (voicedFrames < 6 || userAudio.length < 48000 * 2 * 0.15) {
                        log.info("🗑️ [WebRtcGateway] Discarded transient noise burst (voicedFrames={}, bytes={}) for callId={}",
                                voicedFrames, userAudio.length, callId);
                        return;
                    }

                    // Hard ceiling: Cap audio payload at max 5.5 seconds (528,000 bytes)
                    if (userAudio.length > 528000) {
                        userAudio = Arrays.copyOfRange(userAudio, userAudio.length - 528000, userAudio.length);
                    }

                    // Record user speech into conversation recording accumulator
                    synchronized (session.recordedConversationAudio) {
                        try {
                            session.recordedConversationAudio.write(userAudio);
                        } catch (Exception ignored) {}
                    }

                    session.isProcessingTurn.set(true);
                    
                    final int currentTurnId = session.turnId.incrementAndGet();
                    session.isSpeakingAI.set(false);
                    flushOutboundAudio(callId);
                    
                    long endMs = System.currentTimeMillis();
                    session.userUtteranceEndMs.set(endMs);
                    session.firstRtpSentMs.set(0);
                    
                    // Phase 2: Check if Live Streaming STT has already transcribed this utterance via WebSocket
                    String liveTranscript = null;
                    if (session.liveSttSession != null) {
                        liveTranscript = session.liveSttSession.getAndResetTranscript();
                    }
                    if (liveTranscript != null && !liveTranscript.isBlank()) {
                        log.info("⚡ [WebRtcGateway] Fast-path: Using Live Streaming STT transcript: '{}' for callId={}",
                                liveTranscript, callId);
                    } else {
                        liveTranscript = null;
                    }
                    final String finalLiveTranscript = liveTranscript;

                    log.info("🗣️ [WebRtcGateway] User utterance captured ({} bytes PCM @ 48kHz, ~{}ms, hasLiveTranscript={}), processing AI turn for callId={}",
                            userAudio.length, (userAudio.length / (48 * 2)), (finalLiveTranscript != null), callId);

                    // Wrap raw 16-bit LE PCM in a standard WAV header for fallback STT processing
                    byte[] wavAudio = com.chatcrmlite.backend.utils.WavHeaderUtil.addWavHeader(userAudio, 48000, 1);
                    
                    CompletableFuture.runAsync(() -> {
                        try {
                            WhatsAppVoiceCallBridgeService.WhatsAppVoiceTurnResult turn =
                                    voiceCallBridgeService.processCallTurn(session.tenantId, callId, wavAudio, "audio/wav", finalLiveTranscript, chunk -> {
                                        enqueueOutboundAudio(callId, chunk, currentTurnId);
                                    });
                                    
                            // Verify turn generation ID to prevent race conditions after barge-in or termination
                            if (session.turnId.get() != currentTurnId || !session.running.get()) {
                                log.info("🛑 [WebRtcGateway] Dropping stale AI response (Turn ID changed or terminated)");
                                return;
                            }
                            
                            log.info("🤖 [WebRtcGateway] AI Response for callId={}: {}", callId, turn.aiResponseText());
                            // Do NOT enqueue turn.synthesizedAudio() here because it was already streamed incrementally via the chunk callback!
                            // Doing so would cause the bot to repeat its entire response twice.
                        } catch (Exception e) {
                            log.error("❌ [WebRtcGateway] Error processing user voice turn: {}", e.getMessage());
                        } finally {
                            if (session.turnId.get() == currentTurnId) {
                                session.isProcessingTurn.set(false);
                                // Flush residual echo/noise captured during AI turn before next user input
                                synchronized (session.inboundPcmBuffer) {
                                    if (!session.hasSpokenInTurn) {
                                        session.inboundPcmBuffer.reset();
                                    }
                                }
                            }
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
                final int welcomeTurnId = session.turnId.get();
                WhatsAppVoiceCallBridgeService.WhatsAppVoiceTurnResult turn =
                        voiceCallBridgeService.processCallTurn(session.tenantId, callId, new byte[0], "audio/wav", "Hello, I just connected", chunk -> {
                            enqueueOutboundAudio(callId, chunk, welcomeTurnId);
                        });
                log.info("🤖 [WebRtcGateway] Welcome AI Greeting for callId={}: {}", callId, turn.aiResponseText());
                // Do NOT enqueue turn.synthesizedAudio() here to prevent the bot from repeating the greeting twice!
            } catch (Exception e) {
                log.error("❌ [WebRtcGateway] Error generating welcome turn: {}", e.getMessage());
            }
        });
    }

    public void enqueueOutboundAudio(String callId, byte[] audioBytes, int targetTurnId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session == null || !session.running.get()) return;

        if (session.turnId.get() != targetTurnId) {
            return;
        }

        if (audioBytes != null && audioBytes.length > 0) {
            synchronized (session.recordedConversationAudio) {
                try {
                    session.recordedConversationAudio.write(audioBytes);
                } catch (Exception ignored) {}
            }
        }

        List<byte[]> opusFrames = new ArrayList<>();
        boolean isEndOfStream = (audioBytes == null || audioBytes.length == 0);
        
        synchronized (session.ttsPcmAccumulator) {
            if (session.currentTtsTurnId != targetTurnId) {
                // New turn! Clear accumulator and reset playback flags
                session.ttsPcmAccumulator.reset();
                session.currentTtsTurnId = targetTurnId;
                session.isTtsPlaybackReady.set(false);
                session.isTtsStreamCompleted.set(false);
            }

            if (isEndOfStream) {
                // End of stream signal -> mark stream completed and flush remaining bytes
                session.isTtsStreamCompleted.set(true);
                byte[] remainder = session.ttsPcmAccumulator.toByteArray();
                if (remainder.length > 0) {
                    opusFrames.addAll(OpusAudioEncoder.encodeRawPcmToOpusFrames(remainder, 48000, 1, session.opusEncoder));
                    session.ttsPcmAccumulator.reset();
                }
            } else {
                session.ttsPcmAccumulator.write(audioBytes, 0, audioBytes.length);

                byte[] accumulated = session.ttsPcmAccumulator.toByteArray();
                int bytesPerFrame = 1920; // 960 samples * 2 bytes/sample (20ms @ 48kHz mono)
                int framesToEncode = accumulated.length / bytesPerFrame;
                
                if (framesToEncode > 0) {
                    int bytesToConsume = framesToEncode * bytesPerFrame;
                    byte[] toEncode = Arrays.copyOfRange(accumulated, 0, bytesToConsume);
                    
                    // Keep the remainder for the next chunk
                    session.ttsPcmAccumulator.reset();
                    session.ttsPcmAccumulator.write(accumulated, bytesToConsume, accumulated.length - bytesToConsume);
                    
                    opusFrames.addAll(OpusAudioEncoder.encodeRawPcmToOpusFrames(toEncode, 48000, 1, session.opusEncoder));
                }
            }
        }
        
        // Double check turnId again after expensive encoding
        if (session.turnId.get() != targetTurnId || !session.running.get()) {
            if (!opusFrames.isEmpty()) {
                log.info("🛑 [WebRtcGateway] Dropping {} encoded frames because turnId changed (barge-in)", opusFrames.size());
            }
            return;
        }
        
        // ── Queue Depth Cap ──────────────────────────────────────────────────────
        // At 50 fps (20ms/frame), 300 frames = 6 seconds of audio.
        // If the queue is already this deep, the user can't hear us fast enough.
        final int MAX_QUEUE_DEPTH = 300;
        int dropped = 0;
        for (byte[] frame : opusFrames) {
            if (session.outboundAudioQueue.size() >= MAX_QUEUE_DEPTH) {
                dropped++;
            } else {
                session.outboundAudioQueue.offer(frame);
            }
        }
        
        if (dropped > 0) {
            log.warn("⚠️ [WebRtcGateway] Dropped {} TTS frames (queue full at {}) for callId={} turnId={} — preventing audio stall",
                    dropped, MAX_QUEUE_DEPTH, callId, targetTurnId);
        }

        int currentQueueDepth = session.outboundAudioQueue.size();
        // Pre-roll jitter buffer trigger:
        // Begin playback once we have buffered at least PRE_ROLL_MIN_FRAMES (~240ms) OR when stream completed (short utterance)
        if (currentQueueDepth >= WebRtcMediaSession.PRE_ROLL_MIN_FRAMES || (isEndOfStream && currentQueueDepth > 0)) {
            session.isTtsPlaybackReady.set(true);
            session.isSpeakingAI.set(true);
        }

        if (!opusFrames.isEmpty() && dropped < opusFrames.size()) {
            log.info("🔊 [WebRtcGateway] Enqueued {} Opus frames for callId={} turnId={} (queueDepth={}, ready={})",
                    opusFrames.size() - dropped, callId, targetTurnId, currentQueueDepth, session.isTtsPlaybackReady.get());
        }
    }

    public void flushOutboundAudio(String callId) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) {
            session.outboundAudioQueue.clear();
            session.isTtsPlaybackReady.set(false);
            session.isTtsStreamCompleted.set(false);
            session.isSpeakingAI.set(false);
            synchronized (session.ttsPcmAccumulator) {
                session.ttsPcmAccumulator.reset();
            }
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

    /**
     * Retrieves the recorded call audio (in 48kHz 16-bit mono PCM).
     * If session is terminated, returns cached audio and clears it from cache.
     */
    public byte[] getRecordedAudio(String callId) {
        byte[] recorded = lastRecordedAudios.remove(callId);
        if (recorded != null) {
            return recorded;
        }
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session != null) {
            synchronized (session.recordedConversationAudio) {
                return session.recordedConversationAudio.toByteArray();
            }
        }
        return null;
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
            synchronized (session.recordedConversationAudio) {
                byte[] audio = session.recordedConversationAudio.toByteArray();
                if (audio.length > 0) {
                    lastRecordedAudios.put(callId, audio);
                }
            }
            String reason = deriveFailureReason(session);
            if (reason != null) {
                session.failureReason.compareAndSet(null, reason);
                lastFailureReasons.put(callId, session.failureReason.get());
            }
            lastMediaStates.put(callId, session.state);
            session.running.set(false);
            session.turnId.incrementAndGet(); // Cancel any pending LLM/TTS
            voiceCallBridgeService.terminateSession(callId);
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
            if (session.watchdogTask != null) {
                session.watchdogTask.cancel(true);
            }
            if (session.liveSttSession != null) {
                session.liveSttSession.close();
                session.liveSttSession = null;
            }
            if (session.dtlsTransportAdapter != null) {
                session.dtlsTransportAdapter.close();
            }
            if (session.turnSession != null) {
                session.turnSession.close();
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
                 "srtpDecryptFailures: {}\n" +
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
                session.inboundSrtpPacketsCount.get(), session.outboundSrtpPacketsCount.get(), d.srtpDecryptFailures.get());
    }

    public void handleInboundPacketForCall(String callId, byte[] data) {
        WebRtcMediaSession session = activeSessions.get(callId);
        if (session == null || !session.running.get()) return;
        InetSocketAddress sender = session.remoteAddress;
        processInboundPacket(session, data, sender);
    }

    private void startInboundListener(WebRtcMediaSession session) {
        Thread listenerThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            log.info("👂 [WebRtcGateway] Listening for UDP/RTP packets on port {}", session.socket.getLocalPort());

            while (session.running.get() && !session.socket.isClosed()) {
                try {
                    packet.setData(buffer);
                    packet.setLength(buffer.length);
                    session.socket.receive(packet);
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();
                    processInboundPacket(session, data, sender);
                } catch (Exception e) {
                    if (!session.running.get()) break;
                    log.warn("⚠️ [WebRtcGateway] Inbound UDP receive error: {}", e.getMessage());
                }
            }
        });
        listenerThread.setName("webrtc-media-" + session.callId.substring(0, Math.min(10, session.callId.length())));
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void processInboundPacket(WebRtcMediaSession session, byte[] data, InetSocketAddress sender) {
        if (data == null || data.length == 0 || !session.running.get()) return;

        // Do not let public STUN server responses (port 19302 or port 80) overwrite Meta remoteAddress
        boolean isPublicStunServer = sender != null && (sender.getPort() == 19302 || sender.getPort() == 80);
        if (sender != null && !isPublicStunServer) {
            log.info("📦 [WebRtcGateway] INBOUND PACKET len={} from={} b0=0x{} b1=0x{}",
                    data.length, sender,
                    data.length > 0 ? Integer.toHexString(data[0] & 0xFF) : "0",
                    data.length > 1 ? Integer.toHexString(data[1] & 0xFF) : "0");

            if (session.remoteAddress == null) {
                session.remoteAddress = sender;
                if (session.dtlsTransportAdapter != null) {
                    session.dtlsTransportAdapter.setRemoteAddress(sender);
                }
            } else if (!session.remoteAddress.equals(sender)) {
                // Peer IP or port changed (e.g. NAT binding or IPv4/IPv6 selection)
                session.remoteAddress = sender;
                if (session.dtlsTransportAdapter != null) {
                    session.dtlsTransportAdapter.setRemoteAddress(sender);
                }
            }
        }

        // STUN: Binding Request=0x0001, Binding Success Response=0x0101, Binding Error=0x0111
        if (data.length >= 20 && data[0] == 0x00 && data[1] == 0x01) {
            session.dtlsDiagnostics.inboundStunPackets.incrementAndGet();
            if (session.state == CallMediaState.ICE_CHECKING || session.state == CallMediaState.CREATED) {
                session.state = CallMediaState.ICE_CONNECTED;
            }
            if (sender != null && !isPublicStunServer) {
                session.remoteAddress = sender;
                if (session.dtlsTransportAdapter != null) {
                    session.dtlsTransportAdapter.setRemoteAddress(sender);
                }
            }
            handleStunBindingRequest(session, data, sender != null ? sender : session.remoteAddress);
            return;
        }
        if (data.length >= 20 && data[0] == 0x01 && data[1] == 0x01) {
            session.dtlsDiagnostics.inboundStunPackets.incrementAndGet();
            if (session.state == CallMediaState.ICE_CHECKING || session.state == CallMediaState.CREATED) {
                session.state = CallMediaState.ICE_CONNECTED;
            }
            log.info("✅ [WebRtcGateway] STUN Binding Success Response from {} (ICE path confirmed)", sender);
            if (sender != null && !isPublicStunServer) {
                session.remoteAddress = sender;
                if (session.dtlsTransportAdapter != null) {
                    session.dtlsTransportAdapter.setRemoteAddress(sender);
                }
            }
            return;
        }
        if (data.length >= 20 && data[0] == 0x01 && data[1] == 0x11) {
            log.warn("⚠️ [WebRtcGateway] STUN Binding Error Response (0x0111) received from {} len={}", sender, data.length);
            return;
        }

        int firstByte = data[0] & 0xFF;
        if (firstByte >= 20 && firstByte <= 63) {
            session.dtlsDiagnostics.inboundDtlsPackets.incrementAndGet();
            if (session.dtlsTransportAdapter != null) {
                session.dtlsTransportAdapter.enqueueInbound(data);
            }
            return;
        }

        if (data.length > 12 && ((data[0] & 0xC0) == 0x80)) {
            // RFC 5761 (Demultiplexing RTP and RTCP): 
            // If the 7-bit payload type is 64-95, it's RTCP.
            // If it's 0-63 or 96-127, it's RTP.
            int pt = data[1] & 0xFF;
            int pt7 = pt & 0x7F;
            boolean isRtcp = (pt7 >= 64 && pt7 <= 95);
            if (isRtcp) {
                // SRTCP has a different auth formula — silently skip,
                // we don't need RTCP processing for voice
                return;
            }

            session.inboundSrtpPacketsCount.incrementAndGet();
            session.lastInboundPacketTime = System.currentTimeMillis();

            if (session.receiverSrtpTransformer == null) {
                // Do not treat as media before SRTP_READY
                return;
            }

            byte[] decrypted = session.receiverSrtpTransformer.decryptSrtp(data);
            if (decrypted == null && session.altReceiverSrtpTransformer != null && !session.srtpKeysSwapped.get()) {
                // Auto-recover from client/server key direction mismatch after DTLS
                byte[] alt = session.altReceiverSrtpTransformer.decryptSrtp(data);
                if (alt != null) {
                    log.warn("🔄 [WebRtcGateway] SRTP key direction mismatch detected — swapping TX/RX master keys for callId={}",
                            session.callId);
                    session.senderSrtpTransformer = session.altSenderSrtpTransformer;
                    session.receiverSrtpTransformer = session.altReceiverSrtpTransformer;
                    session.srtpKeysSwapped.set(true);
                    session.dtlsDiagnostics.srtpKeyInitResult.set("SRTP_KEYS_SWAPPED");
                    decrypted = alt;
                }
            }
            if (decrypted == null) {
                session.dtlsDiagnostics.srtpDecryptFailures.incrementAndGet();
                if (session.dtlsDiagnostics.srtpDecryptFailures.get() % 50 == 1) {
                    log.warn("⚠️ [WebRtcGateway] SRTP Decrypt failed for packet (wrong key or corrupted)? pt={} swapped={}",
                            pt, session.srtpKeysSwapped.get());
                }
                return;
            }

            if (decrypted.length > 12) {
                // Strip 12-byte fixed RTP header (CC=0, no extension)
                // Handle CSRC list: lower 4 bits of byte 0 = CC count
                int cc = decrypted[0] & 0x0F;
                int headerLen = 12 + cc * 4;
                // Check for RTP extension bit (bit 4 of byte 0)
                if ((decrypted[0] & 0x10) != 0 && decrypted.length > headerLen + 4) {
                    int extLen = ((decrypted[headerLen + 2] & 0xFF) << 8 | (decrypted[headerLen + 3] & 0xFF)) * 4;
                    headerLen += 4 + extLen;
                }
                if (headerLen >= decrypted.length) return;

                byte[] opusFrame = Arrays.copyOfRange(decrypted, headerLen, decrypted.length);
                
                if (session.inboundSrtpPacketsCount.get() % 50 == 1) {
                    log.info("🛠️ [WebRtcGateway] Pre-decode: pt={} headerLen={} opusLen={} cc={} X={}", 
                        (decrypted[1] & 0x7F), headerLen, opusFrame.length, cc, ((decrypted[0] & 0x10) != 0));
                }

                // Decode Opus frame -> 16-bit PCM at 48kHz mono
                try {
                    if (session.opusDecoder == null) return;
                    short[] pcmSamples = new short[5760]; // Max possible Opus frame size (120ms @ 48kHz mono)
                    int samplesDecoded = session.opusDecoder.decode(opusFrame, 0, opusFrame.length,
                            pcmSamples, 0, 5760, false);
                    if (samplesDecoded > 0) {
                        byte[] pcmBytes = new byte[samplesDecoded * 2];
                        ByteBuffer pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                        long sumSquares = 0;
                        int zeroCrossings = 0;
                        for (int s = 0; s < samplesDecoded; s++) {
                            pcmBuf.putShort(pcmSamples[s]);
                            sumSquares += (long) pcmSamples[s] * pcmSamples[s];
                            if (s > 0) {
                                if ((pcmSamples[s] >= 0 && pcmSamples[s - 1] < 0) || (pcmSamples[s] < 0 && pcmSamples[s - 1] >= 0)) {
                                    zeroCrossings++;
                                }
                            }
                        }
                        
                        double rms = Math.sqrt((double) sumSquares / samplesDecoded);
                        int normalizedZcr = (int) Math.round(((double) zeroCrossings / samplesDecoded) * 960.0);
                        long now = System.currentTimeMillis();
                        
                        // Voice frequency filter:
                        // 20ms frame @ 48kHz mono has 960 samples.
                        // Deep human fundamental (down to 80 Hz) has ZCR ~ 4-5 crossings.
                        // Unvoiced fricatives (s, sh, f up to 6 kHz) have ZCR up to 240 crossings.
                        boolean aiSpeaking = session.isSpeakingAI.get();
                        boolean isVoiceBand = aiSpeaking
                                ? (normalizedZcr >= 10 && normalizedZcr <= 230)
                                : (normalizedZcr >= 5 && normalizedZcr <= 260);

                        // Dual-Threshold VAD:
                        // While AI is speaking, user's phone speaker plays AI audio.
                        // Room echo or background music may be present.
                        // Only loud, intentional human speech (RMS > 2000 or 4.5x noiseFloor) should interrupt AI (Barge-In).
                        // When AI is silent, standard threshold (RMS > 600 or 2.2x noiseFloor) allows natural conversational speech.
                        double threshold = aiSpeaking 
                                ? Math.max(2000.0, session.vadNoiseFloor * 4.5)
                                : Math.max(600.0, session.vadNoiseFloor * 2.2);
                        int requiredConsecutiveFrames = aiSpeaking ? 22 : 6; // 440ms if AI speaking (resilient against echo bursts), 120ms if silent

                        boolean isSpeech = isVoiceBand && (rms > threshold);
                        
                        if (isSpeech) {
                            session.consecutiveSpeechFrames++;
                            session.consecutiveSilenceFrames = 0;
                            session.voicedFramesInUtterance.incrementAndGet();
                            session.lastVoiceActivityTime = now;
                            
                            if (session.consecutiveSpeechFrames >= requiredConsecutiveFrames && !session.hasSpokenInTurn) {
                                session.hasSpokenInTurn = true;
                                session.speechStartTime = now;
                                log.info("📢 [WebRtcGateway] User speech detected (aiSpeaking={})! RMS={} threshold={} ZCR={} noiseFloor={}",
                                        aiSpeaking, String.format("%.1f", rms), String.format("%.1f", threshold), normalizedZcr, String.format("%.1f", session.vadNoiseFloor));
                                
                                // Clear outbound audio
                                session.outboundAudioQueue.clear();
                                session.isTtsPlaybackReady.set(false);
                                session.isTtsStreamCompleted.set(false);
                                
                                if (aiSpeaking) {
                                    session.isProcessingTurn.set(false);
                                    session.turnId.incrementAndGet();
                                    session.isSpeakingAI.set(false);
                                    log.info("🛑 [WebRtcGateway] BARGE-IN: Interrupted AI speech playback.");
                                } else {
                                    log.info("⏳ [WebRtcGateway] User started speaking while AI was silent.");
                                }

                                // Phase 2: Feed pre-roll buffer to Live Streaming STT session
                                if (session.liveSttSession != null && session.liveSttSession.isConnected()) {
                                    synchronized (session.inboundPcmBuffer) {
                                        byte[] preRoll = session.inboundPcmBuffer.toByteArray();
                                        if (preRoll.length > 0) {
                                            session.liveSttSession.sendAudioChunk(preRoll);
                                        }
                                    }
                                }
                            }
                        } else {
                            session.consecutiveSpeechFrames = 0;
                            session.consecutiveSilenceFrames++;
                            
                            // Dynamically adapt noise floor during non-speech periods (capped at 450 to prevent lockouts)
                            // CRITICAL: Do NOT adapt noise floor while AI is speaking, to prevent speaker echo from corrupting the ambient baseline!
                            if (!session.hasSpokenInTurn && !aiSpeaking && session.consecutiveSilenceFrames > 8 && rms > 10.0 && rms < 1200.0) {
                                session.vadNoiseFloor = Math.min(450.0, (0.95 * session.vadNoiseFloor) + (0.05 * rms));
                            }
                        }

                        // Phase 2: Stream incoming 20ms linear16 PCM frame to Live STT WebSocket
                        if (session.hasSpokenInTurn && session.liveSttSession != null && session.liveSttSession.isConnected()) {
                            session.liveSttSession.sendAudioChunk(pcmBytes);
                        }

                        synchronized (session.inboundPcmBuffer) {
                            session.inboundPcmBuffer.write(pcmBytes);
                            // Phase 1 Fix: Rolling pre-roll window while user is not speaking (max 400ms = 38,400 bytes)
                            // When incoming silence/echo accumulates past 48,000 bytes, trim to the latest 38,400 bytes.
                            if (!session.hasSpokenInTurn && session.inboundPcmBuffer.size() > 48000) {
                                byte[] current = session.inboundPcmBuffer.toByteArray();
                                session.inboundPcmBuffer.reset();
                                int keepLen = 38400;
                                int keepOffset = current.length - keepLen;
                                if (keepOffset >= 0) {
                                    session.inboundPcmBuffer.write(current, keepOffset, keepLen);
                                }
                            }
                        }
                    }
                } catch (Exception decodeEx) {
                    log.error("❌ [WebRtcGateway] Opus decode error (packet dropped): {}", decodeEx.getMessage(), decodeEx);
                }
            }
        }
    }

    private void handleStunBindingRequest(WebRtcMediaSession session, byte[] stunRequest, InetSocketAddress sender) {
        try {
            byte[] transactionId = Arrays.copyOfRange(stunRequest, 8, 20);

            ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
            boolean isIpv6 = sender != null && sender.getAddress() instanceof Inet6Address;
            attrStream.write(0x00); attrStream.write(0x20); // XOR-MAPPED-ADDRESS
            if (isIpv6) {
                attrStream.write(0x00); attrStream.write(20); // attr length = 20
                attrStream.write(0x00);
                attrStream.write(0x02); // Family 0x02 = IPv6
                int xorPort = (sender != null ? sender.getPort() : 3480) ^ 0x2112;
                attrStream.write((xorPort >> 8) & 0xFF);
                attrStream.write(xorPort & 0xFF);
                byte[] ipBytes = sender != null ? sender.getAddress().getAddress() : new byte[16];
                byte[] magic = new byte[]{(byte) 0x21, (byte) 0x12, (byte) 0xA4, (byte) 0x42};
                for (int i = 0; i < 4; i++) {
                    attrStream.write(((ipBytes[i] & 0xFF) ^ (magic[i] & 0xFF)) & 0xFF);
                }
                for (int i = 0; i < 12; i++) {
                    attrStream.write(((ipBytes[4 + i] & 0xFF) ^ (transactionId[i] & 0xFF)) & 0xFF);
                }
            } else {
                attrStream.write(0x00); attrStream.write(0x08); // attr length = 8
                attrStream.write(0x00);
                attrStream.write(0x01); // Family 0x01 = IPv4
                int xorPort = (sender != null ? sender.getPort() : 3480) ^ 0x2112;
                attrStream.write((xorPort >> 8) & 0xFF);
                attrStream.write(xorPort & 0xFF);
                byte[] ipBytes = sender != null ? sender.getAddress().getAddress() : new byte[]{57, (byte)144, (byte)143, 49};
                byte[] magic = new byte[]{(byte) 0x21, (byte) 0x12, (byte) 0xA4, (byte) 0x42};
                for (int i = 0; i < 4; i++) {
                    attrStream.write(((ipBytes[i] & 0xFF) ^ (magic[i] & 0xFF)) & 0xFF);
                }
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

                // RFC 5389 §15.5 (FINGERPRINT): Header length MUST be hmacLength (excluding FINGERPRINT) during CRC
                int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                buf.putShort((short) 0x8028);
                buf.putShort((short) 4);
                buf.putInt(crc);

                // AFTER CRC computation, update header length to totalAttrLen (including FINGERPRINT)
                forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                forHmac[3] = (byte) (totalAttrLen & 0xFF);
            }

            byte[] responseBytes = buf.array();
            session.sendMediaPacket(responseBytes);
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

    private static boolean isPrivateOrLoopback(String ip) {
        if (ip == null || ip.isBlank() || ip.startsWith("127.") || ip.equals("localhost")) return true;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private List<InetSocketAddress> parseAllRemoteCandidates(String sdpOffer) {
        List<InetSocketAddress> list = new ArrayList<>();
        if (sdpOffer == null) return list;
        Matcher m = Pattern.compile("(?m)^a=candidate:\\S+\\s+\\d+\\s+udp\\s+\\d+\\s+(\\S+)\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(sdpOffer);
        while (m.find()) {
            try {
                String ip = m.group(1);
                int port = Integer.parseInt(m.group(2));
                list.add(new InetSocketAddress(InetAddress.getByName(ip), port));
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            Matcher cMatcher = Pattern.compile("(?m)^c=IN IP[46] (\\S+)").matcher(sdpOffer);
            Matcher mMatcher = Pattern.compile("(?m)^m=audio (\\d+)").matcher(sdpOffer);
            if (cMatcher.find() && mMatcher.find()) {
                try {
                    list.add(new InetSocketAddress(InetAddress.getByName(cMatcher.group(1)), Integer.parseInt(mMatcher.group(1))));
                } catch (Exception ignored) {
                }
            }
        }
        return list;
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
        final List<InetSocketAddress> allRemoteCandidates;
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
        volatile SrtpTransformer altSenderSrtpTransformer;
        volatile SrtpTransformer altReceiverSrtpTransformer;
        final AtomicBoolean srtpKeysSwapped = new AtomicBoolean(false);
        volatile SrtpTransformer receiverSrtpTransformer;
        public volatile TurnSession turnSession;
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
        volatile long lastVoiceActivityTime = 0;
        volatile long speechStartTime = 0;
        volatile long lastRtpSendTime = 0;
        volatile boolean hasSpokenInTurn = false;
        
        // VAD State tracking
        double vadNoiseFloor = 40.0;
        int consecutiveSpeechFrames = 0;
        int consecutiveSilenceFrames = 0;
        final AtomicInteger voicedFramesInUtterance = new AtomicInteger(0);
        final AtomicBoolean isTtsPlaybackReady = new AtomicBoolean(false);
        final AtomicBoolean isTtsStreamCompleted = new AtomicBoolean(false);
        
        final long ssrc = 850231558L;
        final ConcurrentLinkedQueue<byte[]> outboundAudioQueue = new ConcurrentLinkedQueue<>();
        final ByteArrayOutputStream inboundPcmBuffer = new ByteArrayOutputStream();
        final ByteArrayOutputStream ttsPcmAccumulator = new ByteArrayOutputStream();
        final ByteArrayOutputStream recordedConversationAudio = new ByteArrayOutputStream();
        volatile int currentTtsTurnId = -1;
        final AtomicBoolean isProcessingTurn = new AtomicBoolean(false);
        final AtomicBoolean isSpeakingAI = new AtomicBoolean(false);
        final AtomicInteger turnId = new AtomicInteger(0);
        volatile DeepgramLiveSttSession liveSttSession;
        final AtomicLong dtxTickCount = new AtomicLong(0);
        final AtomicLong lastRtpSendWallClockMs = new AtomicLong(0);
        final AtomicLong userUtteranceEndMs = new AtomicLong(0);
        final AtomicLong firstRtpSentMs = new AtomicLong(0);
        ScheduledFuture<?> outboundTask;
        ScheduledFuture<?> vadTask;
        ScheduledFuture<?> iceCheckTask;
        ScheduledFuture<?> watchdogTask;
        final OpusDecoder opusDecoder;
        final io.github.jaredmdobson.concentus.OpusEncoder opusEncoder;

        WebRtcMediaSession(String callId, UUID tenantId, String fromWaId, DatagramSocket socket,
                           InetSocketAddress remoteAddress, List<InetSocketAddress> allRemoteCandidates,
                           int payloadType, String ufrag, String pwd, String remoteUfrag, String remotePwd,
                           String remoteFingerprint, String localFingerprint, boolean iceControlling) {
            this.callId = callId;
            this.tenantId = tenantId;
            this.fromWaId = fromWaId;
            this.socket = socket;
            this.remoteAddress = remoteAddress;
            this.allRemoteCandidates = (allRemoteCandidates != null && !allRemoteCandidates.isEmpty())
                    ? new ArrayList<>(allRemoteCandidates)
                    : (remoteAddress != null ? Collections.singletonList(remoteAddress) : Collections.emptyList());
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
            OpusDecoder dec;
            try {
                dec = new OpusDecoder(48000, 1); // 48kHz, mono
            } catch (Exception e) {
                log.error("❌ [WebRtcGateway] Failed to initialize OpusDecoder: {}", e.getMessage(), e);
                dec = null;
                // Will be guarded by null check in RTP receive loop
            }
            this.opusDecoder = dec;

            io.github.jaredmdobson.concentus.OpusEncoder enc = null;
            try {
                enc = new io.github.jaredmdobson.concentus.OpusEncoder(48000, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_VOIP);
                enc.setBitrate(24000);
                enc.setSignalType(io.github.jaredmdobson.concentus.OpusSignal.OPUS_SIGNAL_VOICE);
            } catch (Exception e) {
                log.error("❌ [WebRtcGateway] Failed to initialize OpusEncoder: {}", e.getMessage(), e);
            }
            this.opusEncoder = enc;
        }

        WebRtcMediaSession(String callId, UUID tenantId, String fromWaId, DatagramSocket socket,
                           InetSocketAddress remoteAddress, int payloadType, String ufrag, String pwd,
                           String remoteUfrag, String remotePwd, String remoteFingerprint,
                           String localFingerprint, boolean iceControlling) {
            this(callId, tenantId, fromWaId, socket, remoteAddress,
                    remoteAddress != null ? Collections.singletonList(remoteAddress) : Collections.emptyList(),
                    payloadType, ufrag, pwd, remoteUfrag, remotePwd, remoteFingerprint, localFingerprint, iceControlling);
        }

        private static final byte[] OPUS_SILENCE_FRAME = new byte[] { (byte) 0xF8, (byte) 0xFF, (byte) 0xFE };
        public static final int PRE_ROLL_MIN_FRAMES = 12; // ~240ms pre-roll jitter buffer

        public static class OutboundRtpFrame {
            public final byte[] packetData;
            public final boolean isSilence;

            public OutboundRtpFrame(byte[] packetData, boolean isSilence) {
                this.packetData = packetData;
                this.isSilence = isSilence;
            }
        }

        public OutboundRtpFrame buildNextRtpPacket(int currentTimestamp) {
            byte[] payload = null;
            if (isTtsPlaybackReady.get()) {
                payload = outboundAudioQueue.poll();
            }
            boolean isSilence = false;
            if (payload == null) {
                payload = OPUS_SILENCE_FRAME;
                isSilence = true;
                // Only mark AI speaking as finished if Deepgram TTS stream has fully finished delivering chunks
                // AND the outbound queue has been completely drained
                if (isTtsStreamCompleted.get() && outboundAudioQueue.isEmpty()) {
                    isSpeakingAI.set(false);
                    isTtsPlaybackReady.set(false);
                }
            } else {
                isSpeakingAI.set(true);
            }

            ByteBuffer buf = ByteBuffer.allocate(12 + payload.length);
            buf.put((byte) 0x80);
            buf.put((byte) (payloadType & 0x7F));
            buf.putShort((short) sequenceNumber.getAndIncrement());
            buf.putInt(currentTimestamp);
            buf.putInt((int) ssrc);
            buf.put(payload);
            return new OutboundRtpFrame(buf.array(), isSilence);
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

                long candPriority = (turnSession != null) ? 1853817087L : 2130706431L;
                attrStream.write(0x00); attrStream.write(0x24);
                attrStream.write(0x00); attrStream.write(0x04);
                attrStream.write((byte) ((candPriority >> 24) & 0xFF));
                attrStream.write((byte) ((candPriority >> 16) & 0xFF));
                attrStream.write((byte) ((candPriority >> 8) & 0xFF));
                attrStream.write((byte) (candPriority & 0xFF));

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

                    // RFC 5389 §15.5 (FINGERPRINT): Header length MUST be hmacLength (excluding FINGERPRINT) during CRC
                    int crc = computeStunFingerprint(forHmac, 20 + rawAttrs.length + 24);
                    buf.putShort((short) 0x8028);
                    buf.putShort((short) 4);
                    buf.putInt(crc);

                    // AFTER CRC computation, update header length to totalAttrLen (including FINGERPRINT)
                    forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
                    forHmac[3] = (byte) (totalAttrLen & 0xFF);
                }

                return buf.array();
            } catch (Exception e) {
                log.warn("Error building STUN binding request: {}", e.getMessage());
                return new byte[0];
            }
        }

        public void sendMediaPacket(byte[] data) {
            sendMediaPacket(data, remoteAddress);
        }

        public void sendMediaPacket(byte[] data, InetSocketAddress destination) {
            if (!running.get() || data == null || data.length == 0) return;
            // When TURN is active, SDP advertises ONLY the relay candidate — Meta replies only there.
            // Dual-sending direct UDP breaks ICE/DTLS association (inboundStun=0 / inboundDtls=0).
            if (turnSession != null && turnSession.isActive()) {
                try {
                    turnSession.send(data);
                } catch (Exception e) {
                    log.warn("⚠️ [WebRtcGateway] Failed to send packet via TURN session: {}", e.getMessage());
                }
                return;
            }
            InetSocketAddress dest = destination != null ? destination : remoteAddress;
            if (socket != null && !socket.isClosed() && dest != null) {
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length, dest);
                    socket.send(packet);
                } catch (Exception e) {
                    log.debug("Direct UDP send failed to {}: {}", dest, e.getMessage());
                }
            }
        }

        public void sendStunBindingPing(InetSocketAddress destination) {
            if (destination == null || !running.get()) return;
            try {
                byte[] packetData = buildStunBindingRequest();
                if (packetData.length > 0) {
                    boolean viaTurn = turnSession != null && turnSession.isActive();
                    sendMediaPacket(packetData, destination);
                    dtlsDiagnostics.outboundStunPackets.incrementAndGet();
                    log.info("📡 [WebRtcGateway] STUN_REQUEST_SENT: role={} via={} USE-CANDIDATE to Meta at {}",
                            iceControlling ? "CONTROLLING" : "CONTROLLED",
                            viaTurn ? "TURN" : "UDP",
                            destination);
                }
            } catch (Exception e) {
                log.warn("⚠️ [WebRtcGateway] Failed to send STUN binding ping: {}", e.getMessage());
            }
        }
    }
}
