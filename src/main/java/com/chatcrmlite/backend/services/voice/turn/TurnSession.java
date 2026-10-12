package com.chatcrmlite.backend.services.voice.turn;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Enterprise RFC 5766 / RFC 8656 TURN Session over TCP.
 * Relays UDP media to/from Meta through public TURN relay, bypassing Symmetric NAT and CGNAT restrictions.
 */
@Slf4j
public class TurnSession implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();

    @Getter
    private final InetSocketAddress relayedAddress;
    @Getter
    private final InetSocketAddress peerAddress;
    private final String username;
    private final String realm;
    private final String nonce;
    private final byte[] hmacKey;
    private final Consumer<byte[]> packetConsumer;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "turn-refresh-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> refreshTask;
    private Thread readerThread;

    public TurnSession(Socket socket,
                       InetSocketAddress relayedAddress,
                       InetSocketAddress peerAddress,
                       String username,
                       String realm,
                       String nonce,
                       byte[] hmacKey,
                       Consumer<byte[]> packetConsumer) {
        this.socket = socket;
        this.relayedAddress = relayedAddress;
        this.peerAddress = peerAddress;
        this.username = username;
        this.realm = realm;
        this.nonce = nonce;
        this.hmacKey = hmacKey;
        this.packetConsumer = packetConsumer;

        try {
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        } catch (IOException e) {
            throw new RuntimeException("Failed to obtain streams from TURN socket: " + e.getMessage(), e);
        }

        startReader();
        startRefreshTask();
    }

    public boolean isActive() {
        return active.get() && !socket.isClosed();
    }

    /**
     * Sends raw application packet (STUN, DTLS, SRTP) wrapped in an RFC 5766 ChannelData frame over TCP.
     */
    public void send(byte[] payload) {
        if (!isActive() || payload == null || payload.length == 0) return;
        try {
            int pad = (4 - (payload.length % 4)) % 4;
            ByteBuffer buf = ByteBuffer.allocate(4 + payload.length + pad);
            buf.putShort((short) 0x4000); // Channel 0x4000 bound to peer
            buf.putShort((short) payload.length);
            buf.put(payload);
            for (int i = 0; i < pad; i++) buf.put((byte) 0);

            byte[] frame = buf.array();
            synchronized (sendLock) {
                out.write(frame);
                out.flush();
            }
            log.info("🚀 [TurnSession] Outbound ChannelData len={} to peer {}", payload.length, peerAddress);
        } catch (Exception e) {
            if (active.get()) {
                log.warn("⚠️ [TurnSession] Failed to send ChannelData frame: {}", e.getMessage());
            }
        }
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            log.info("👂 [TurnSession] Starting TURN TCP reader for peer {} via relay {}", peerAddress, relayedAddress);
            try {
                // Remove soTimeout for long-lived media reception
                socket.setSoTimeout(0);

                while (active.get() && !socket.isClosed()) {
                    byte[] hdr = in.readNBytes(4);
                    if (hdr.length < 4) {
                        log.info("🛑 [TurnSession] TCP stream closed by TURN relay");
                        break;
                    }

                    int channelOrType = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
                    int len = ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);

                    if (channelOrType >= 0x4000 && channelOrType <= 0x7FFE) {
                        // ChannelData message (RFC 5766 §11.5)
                        byte[] payload = in.readNBytes(len);
                        int pad = (4 - (len % 4)) % 4;
                        if (pad > 0) {
                            in.readNBytes(pad); // Skip padding bytes
                        }

                        if (payload.length == len && packetConsumer != null) {
                            log.info("📦 [TurnSession] Inbound ChannelData len={} from peer {}", len, peerAddress);
                            packetConsumer.accept(payload);
                        }
                    } else if ((channelOrType & 0xC000) == 0) {
                        // STUN message (e.g. DataIndication 0x0017, Refresh response 0x0104)
                        byte[] stunHdrRemainder = in.readNBytes(16);
                        byte[] attrs = in.readNBytes(len);

                        if (channelOrType == 0x0017) {
                            // DataIndication: extract DATA attribute (0x0013)
                            byte[] dataPayload = extractDataAttribute(attrs);
                            if (dataPayload != null && packetConsumer != null) {
                                log.info("📦 [TurnSession] Inbound DataIndication len={} from peer {}", dataPayload.length, peerAddress);
                                packetConsumer.accept(dataPayload);
                            }
                        } else if (channelOrType == 0x0104) {
                            log.debug("🔄 [TurnSession] Received TURN Refresh Success Response");
                        }
                    } else {
                        // Unrecognized frame, skip length if possible
                        log.warn("⚠️ [TurnSession] Unrecognized TURN frame header: 0x{} len={}",
                                Integer.toHexString(channelOrType), len);
                        if (len > 0) {
                            in.readNBytes(len);
                        }
                    }
                }
            } catch (Exception e) {
                if (active.get()) {
                    log.debug("🛑 [TurnSession] Reader loop ended: {}", e.getMessage());
                }
            } finally {
                close();
            }
        }, "turn-reader-" + peerAddress.getPort());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private byte[] extractDataAttribute(byte[] attrs) {
        int offset = 0;
        while (offset + 4 <= attrs.length) {
            int attrType = ((attrs[offset] & 0xFF) << 8) | (attrs[offset + 1] & 0xFF);
            int attrLen = ((attrs[offset + 2] & 0xFF) << 8) | (attrs[offset + 3] & 0xFF);
            if (attrType == 0x0013 && offset + 4 + attrLen <= attrs.length) { // DATA
                return Arrays.copyOfRange(attrs, offset + 4, offset + 4 + attrLen);
            }
            offset += 4 + attrLen;
            if (attrLen % 4 != 0) offset += (4 - (attrLen % 4));
        }
        return null;
    }

    private void startRefreshTask() {
        refreshTask = refreshScheduler.scheduleAtFixedRate(() -> {
            if (!active.get() || socket.isClosed()) return;
            try {
                sendRefreshRequest(600);
            } catch (Exception e) {
                log.warn("⚠️ [TurnSession] Failed to send TURN refresh request: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void sendRefreshRequest(int lifetimeSeconds) throws Exception {
        ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
        // LIFETIME (0x000D)
        attrStream.write(0x00); attrStream.write(0x0D);
        attrStream.write(0x00); attrStream.write(0x04);
        attrStream.write((lifetimeSeconds >> 24) & 0xFF);
        attrStream.write((lifetimeSeconds >> 16) & 0xFF);
        attrStream.write((lifetimeSeconds >> 8) & 0xFF);
        attrStream.write(lifetimeSeconds & 0xFF);

        writeStringAttr(attrStream, 0x0006, username);
        writeStringAttr(attrStream, 0x0014, realm);
        writeStringAttr(attrStream, 0x0015, nonce);

        byte[] rawAttrs = attrStream.toByteArray();
        ByteBuffer req = ByteBuffer.allocate(20 + rawAttrs.length + 24);
        req.putShort((short) 0x0004); // Refresh Request
        req.putShort((short) (rawAttrs.length + 24));
        req.putInt(0x2112A442);

        byte[] txId = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(txId);
        req.put(txId);
        req.put(rawAttrs);

        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(hmacKey, "HmacSHA1"));
        byte[] integrity = hmac.doFinal(Arrays.copyOfRange(req.array(), 0, 20 + rawAttrs.length));
        req.putShort((short) 0x0008);
        req.putShort((short) 20);
        req.put(integrity);

        byte[] bytes = req.array();
        synchronized (sendLock) {
            out.write(bytes);
            out.flush();
        }
        log.debug("🔄 [TurnSession] Sent TURN Refresh Request (lifetime={}s)", lifetimeSeconds);
    }

    private static void writeStringAttr(ByteArrayOutputStream os, int type, String val) throws Exception {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        os.write((type >> 8) & 0xFF); os.write(type & 0xFF);
        os.write((bytes.length >> 8) & 0xFF); os.write(bytes.length & 0xFF);
        os.write(bytes);
        int pad = (4 - (bytes.length % 4)) % 4;
        for (int i = 0; i < pad; i++) os.write(0);
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            log.info("🔌 [TurnSession] Closing TURN session for peer {}", peerAddress);
            if (refreshTask != null) {
                refreshTask.cancel(true);
            }
            refreshScheduler.shutdownNow();
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (Exception ignored) {}
        }
    }
}
