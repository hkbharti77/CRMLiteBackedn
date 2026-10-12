package com.chatcrmlite.backend.services.voice.turn;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Enterprise TURN Client for WebRTC NAT Traversal (RFC 5766 / RFC 8656).
 * Handles TCP allocation, authentication, ChannelBind (0x4000), and CreatePermission against Metered.ca TURN relay.
 */
@Slf4j
@Service
public class TurnClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;

    /**
     * Allocates a TURN session for the given peer and returns a connected TurnSession.
     * Returns null if allocation fails, allowing graceful fallback to direct UDP.
     */
    public TurnSession allocateSession(String host,
                                       int port,
                                       String username,
                                       String credential,
                                       InetSocketAddress peerAddress,
                                       List<InetSocketAddress> additionalPeers,
                                       Consumer<byte[]> packetConsumer) {
        if (host == null || host.isBlank() || username == null || credential == null || peerAddress == null) {
            log.warn("⚠️ [TurnClient] Missing required TURN configuration or peer address");
            return null;
        }

        long start = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = connectSocket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(4000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Step 1: Unauthenticated Allocate Request to obtain Realm & Nonce
            byte[] txId = new byte[12];
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);

            ByteBuffer req = ByteBuffer.allocate(28);
            req.putShort((short) 0x0003); // Allocate Request
            req.putShort((short) 8);      // Attribute length
            req.putInt(STUN_MAGIC_COOKIE);
            req.put(txId);
            // REQUESTED-TRANSPORT attribute (0x0019): UDP (17)
            req.putShort((short) 0x0019);
            req.putShort((short) 4);
            req.put((byte) 17); // UDP
            req.put((byte) 0); req.put((byte) 0); req.put((byte) 0);

            out.write(req.array());
            out.flush();

            byte[] header = in.readNBytes(20);
            if (header.length < 20) {
                throw new IllegalStateException("Unexpected EOF reading unauthenticated Allocate response header");
            }
            int msgLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] body = in.readNBytes(msgLen);

            String realm = null;
            String nonce = null;
            int offset = 0;
            while (offset + 4 <= body.length) {
                int attrType = ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
                int attrLen = ((body[offset + 2] & 0xFF) << 8) | (body[offset + 3] & 0xFF);
                if (attrType == 0x0014) { // REALM
                    realm = new String(body, offset + 4, attrLen, StandardCharsets.UTF_8);
                } else if (attrType == 0x0015) { // NONCE
                    nonce = new String(body, offset + 4, attrLen, StandardCharsets.UTF_8);
                }
                offset += 4 + attrLen;
                if (attrLen % 4 != 0) offset += (4 - (attrLen % 4));
            }

            if (realm == null || nonce == null) {
                throw new IllegalStateException("TURN server did not provide REALM or NONCE in 401 response");
            }

            // Derive Long-Term Key: MD5(username + ":" + realm + ":" + credential)
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hmacKey = md5.digest((username + ":" + realm + ":" + credential).getBytes(StandardCharsets.UTF_8));

            // Step 2: Authenticated Allocate Request
            ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
            attrStream.write(0x00); attrStream.write(0x19); attrStream.write(0x00); attrStream.write(0x04);
            attrStream.write(17); attrStream.write(0); attrStream.write(0); attrStream.write(0);
            writeStringAttr(attrStream, 0x0006, username);
            writeStringAttr(attrStream, 0x0014, realm);
            writeStringAttr(attrStream, 0x0015, nonce);

            byte[] rawAttrs = attrStream.toByteArray();
            ByteBuffer authReq = ByteBuffer.allocate(20 + rawAttrs.length + 24);
            authReq.putShort((short) 0x0003); // Allocate Request
            authReq.putShort((short) (rawAttrs.length + 24));
            authReq.putInt(STUN_MAGIC_COOKIE);
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);
            authReq.put(txId);
            authReq.put(rawAttrs);

            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(hmacKey, "HmacSHA1"));
            byte[] integrity = hmac.doFinal(Arrays.copyOfRange(authReq.array(), 0, 20 + rawAttrs.length));
            authReq.putShort((short) 0x0008); // MESSAGE-INTEGRITY
            authReq.putShort((short) 20);
            authReq.put(integrity);

            out.write(authReq.array());
            out.flush();

            header = in.readNBytes(20);
            int respType = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
            msgLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            body = in.readNBytes(msgLen);

            if (respType != 0x0103) {
                throw new IllegalStateException("TURN Allocate rejected with response type: 0x" + Integer.toHexString(respType));
            }

            // Parse XOR-RELAYED-ADDRESS (0x0016)
            String relayIp = null;
            int relayPort = 0;
            offset = 0;
            while (offset + 4 <= body.length) {
                int attrType = ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
                int attrLen = ((body[offset + 2] & 0xFF) << 8) | (body[offset + 3] & 0xFF);
                if (attrType == 0x0016) {
                    int xorPort = ((body[offset + 6] & 0xFF) << 8) | (body[offset + 7] & 0xFF);
                    relayPort = xorPort ^ (STUN_MAGIC_COOKIE >> 16);
                    int b1 = (body[offset + 8] & 0xFF) ^ 0x21;
                    int b2 = (body[offset + 9] & 0xFF) ^ 0x12;
                    int b3 = (body[offset + 10] & 0xFF) ^ 0xA4;
                    int b4 = (body[offset + 11] & 0xFF) ^ 0x42;
                    relayIp = b1 + "." + b2 + "." + b3 + "." + b4;
                }
                offset += 4 + attrLen;
                if (attrLen % 4 != 0) offset += (4 - (attrLen % 4));
            }

            if (relayIp == null || relayPort == 0) {
                throw new IllegalStateException("Failed to parse XOR-RELAYED-ADDRESS from Allocate response");
            }
            InetSocketAddress relayedAddress = new InetSocketAddress(relayIp, relayPort);

            // Step 3: CreatePermission for primary peer
            sendCreatePermission(out, in, peerAddress, username, realm, nonce, hmacKey);

            // Additional peers (if Meta provides alternative candidates)
            if (additionalPeers != null) {
                for (InetSocketAddress extraPeer : additionalPeers) {
                    if (extraPeer != null && !extraPeer.equals(peerAddress) && (extraPeer.getAddress() instanceof java.net.Inet4Address)) {
                        try {
                            sendCreatePermission(out, in, extraPeer, username, realm, nonce, hmacKey);
                        } catch (Exception e) {
                            log.debug("Additional CreatePermission for {} failed: {}", extraPeer, e.getMessage());
                        }
                    }
                }
            }

            // Step 4: ChannelBind (0x0009) to Channel 0x4000 for primary peer
            sendChannelBind(out, in, (short) 0x4000, peerAddress, username, realm, nonce, hmacKey);

            long duration = System.currentTimeMillis() - start;
            log.info("🎉 [TurnClient] TURN Allocation SUCCESS in {}ms: relayedAddress={} peerAddress={}",
                    duration, relayedAddress, peerAddress);

            return new TurnSession(socket, relayedAddress, peerAddress, username, realm, nonce, hmacKey, packetConsumer);

        } catch (Exception e) {
            log.error("❌ [TurnClient] Failed to allocate TURN session: {}", e.getMessage(), e);
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private Socket connectSocket(String host, int port) throws Exception {
        if (port == 443) {
            try {
                log.info("🔐 [TurnClient] Connecting to TURN over TLS (TURNS) on {}:443...", host);
                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket sslSocket = (SSLSocket) sf.createSocket();
                sslSocket.connect(new InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS);
                sslSocket.startHandshake();
                return sslSocket;
            } catch (Exception e) {
                log.warn("⚠️ [TurnClient] TLS on port 443 failed ({}), falling back to plain TCP port 80...", e.getMessage());
                Socket plain = new Socket();
                plain.connect(new InetSocketAddress(host, 80), CONNECT_TIMEOUT_MS);
                return plain;
            }
        } else {
            log.info("🌐 [TurnClient] Connecting to TURN over plain TCP on {}:{}...", host, port);
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return plain;
        }
    }

    private void sendCreatePermission(OutputStream out, InputStream in, InetSocketAddress peer,
                                       String username, String realm, String nonce, byte[] hmacKey) throws Exception {
        ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
        writeXorPeerAddress(attrStream, peer);
        writeStringAttr(attrStream, 0x0006, username);
        writeStringAttr(attrStream, 0x0014, realm);
        writeStringAttr(attrStream, 0x0015, nonce);

        byte[] rawAttrs = attrStream.toByteArray();
        ByteBuffer permReq = ByteBuffer.allocate(20 + rawAttrs.length + 24);
        permReq.putShort((short) 0x0008); // CreatePermission Request
        permReq.putShort((short) (rawAttrs.length + 24));
        permReq.putInt(STUN_MAGIC_COOKIE);
        byte[] txId = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);
        permReq.put(txId);
        permReq.put(rawAttrs);

        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(hmacKey, "HmacSHA1"));
        byte[] integrity = hmac.doFinal(Arrays.copyOfRange(permReq.array(), 0, 20 + rawAttrs.length));
        permReq.putShort((short) 0x0008);
        permReq.putShort((short) 20);
        permReq.put(integrity);

        out.write(permReq.array());
        out.flush();

        byte[] header = in.readNBytes(20);
        int respType = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int msgLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = in.readNBytes(msgLen);

        if (respType != 0x0108) {
            log.warn("⚠️ [TurnClient] CreatePermission returned non-success code: 0x{}", Integer.toHexString(respType));
        } else {
            log.info("✅ [TurnClient] CreatePermission SUCCESS for peer {}", peer);
        }
    }

    private void sendChannelBind(OutputStream out, InputStream in, short channelNumber, InetSocketAddress peer,
                                 String username, String realm, String nonce, byte[] hmacKey) throws Exception {
        ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
        // CHANNEL-NUMBER (0x000C)
        attrStream.write(0x00); attrStream.write(0x0C);
        attrStream.write(0x00); attrStream.write(0x04);
        attrStream.write((channelNumber >> 8) & 0xFF);
        attrStream.write(channelNumber & 0xFF);
        attrStream.write(0x00); attrStream.write(0x00); // RFFU 2 bytes 0

        writeXorPeerAddress(attrStream, peer);
        writeStringAttr(attrStream, 0x0006, username);
        writeStringAttr(attrStream, 0x0014, realm);
        writeStringAttr(attrStream, 0x0015, nonce);

        byte[] rawAttrs = attrStream.toByteArray();
        ByteBuffer cbReq = ByteBuffer.allocate(20 + rawAttrs.length + 24);
        cbReq.putShort((short) 0x0009); // ChannelBind Request
        cbReq.putShort((short) (rawAttrs.length + 24));
        cbReq.putInt(STUN_MAGIC_COOKIE);
        byte[] txId = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);
        cbReq.put(txId);
        cbReq.put(rawAttrs);

        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(hmacKey, "HmacSHA1"));
        byte[] integrity = hmac.doFinal(Arrays.copyOfRange(cbReq.array(), 0, 20 + rawAttrs.length));
        cbReq.putShort((short) 0x0008);
        cbReq.putShort((short) 20);
        cbReq.put(integrity);

        out.write(cbReq.array());
        out.flush();

        byte[] header = in.readNBytes(20);
        int respType = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int msgLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = in.readNBytes(msgLen);

        if (respType != 0x0109) {
            log.warn("⚠️ [TurnClient] ChannelBind returned non-success code: 0x{}", Integer.toHexString(respType));
        } else {
            log.info("✅ [TurnClient] ChannelBind SUCCESS (channel=0x{}) for peer {}",
                    Integer.toHexString(channelNumber & 0xFFFF), peer);
        }
    }

    private void writeXorPeerAddress(ByteArrayOutputStream os, InetSocketAddress peer) throws Exception {
        os.write(0x00); os.write(0x12); // XOR-PEER-ADDRESS
        os.write(0x00); os.write(0x08); // Len 8
        os.write(0x00); os.write(0x01); // Family IPv4
        int peerPort = peer.getPort() ^ 0x2112;
        os.write((peerPort >> 8) & 0xFF);
        os.write(peerPort & 0xFF);
        byte[] ipBytes = peer.getAddress().getAddress();
        os.write((ipBytes[0] & 0xFF) ^ 0x21);
        os.write((ipBytes[1] & 0xFF) ^ 0x12);
        os.write((ipBytes[2] & 0xFF) ^ 0xA4);
        os.write((ipBytes[3] & 0xFF) ^ 0x42);
    }

    private static void writeStringAttr(ByteArrayOutputStream os, int type, String val) throws Exception {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        os.write((type >> 8) & 0xFF); os.write(type & 0xFF);
        os.write((bytes.length >> 8) & 0xFF); os.write(bytes.length & 0xFF);
        os.write(bytes);
        int pad = (4 - (bytes.length % 4)) % 4;
        for (int i = 0; i < pad; i++) os.write(0);
    }
}
