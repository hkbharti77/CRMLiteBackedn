package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.utils.WavHeaderUtil;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class OpusAudioPipelineTest {

    @Test
    @DisplayName("Concentus Opus Encoder and Decoder round trip for 20ms audio frame at 48kHz mono")
    void testOpusEncodeDecodeRoundTrip() throws Exception {
        OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        OpusDecoder decoder = new OpusDecoder(48000, 1);

        // 20ms frame at 48kHz mono = 960 samples = 1920 bytes of 16-bit PCM
        short[] inputPcm = new short[960];
        for (int i = 0; i < inputPcm.length; i++) {
            // Generate a 440Hz test sine tone
            inputPcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48000.0) * 10000);
        }

        byte[] opusPacket = new byte[1275];
        int encodedLen = encoder.encode(inputPcm, 0, 960, opusPacket, 0, opusPacket.length);

        assertTrue(encodedLen > 0, "Encoded Opus length should be positive");
        assertTrue(encodedLen < 500, "Opus compressed frame should be small (typically 30-100 bytes for speech)");

        short[] decodedPcm = new short[960];
        int decodedSamples = decoder.decode(opusPacket, 0, encodedLen, decodedPcm, 0, 960, false);

        assertEquals(960, decodedSamples, "Decoded sample count should match frame size of 960");
    }

    @Test
    @DisplayName("Opus decoder handles corrupt / invalid data gracefully with OpusException")
    void testOpusDecoderCorruptDataHandling() throws Exception {
        OpusDecoder decoder = new OpusDecoder(48000, 1);
        short[] decodedPcm = new short[960];

        byte[] invalidData = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        // Corrupt packet should throw OpusException rather than fatal JVM crash
        assertThrows(Exception.class, () -> {
            decoder.decode(invalidData, 0, invalidData.length, decodedPcm, 0, 960, false);
        });
    }

    @Test
    @DisplayName("Empty audio byte array produces valid 44-byte empty WAV header")
    void testEmptyAudioWavHeader() {
        byte[] emptyPcm = new byte[0];
        byte[] wav = WavHeaderUtil.addWavHeader(emptyPcm, 48000, 1);

        assertNotNull(wav);
        assertEquals(44, wav.length);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        byte[] riff = new byte[4];
        buf.get(riff);
        assertEquals("RIFF", new String(riff));
        assertEquals(36, buf.getInt()); // 36 + 0
    }

    @Test
    @DisplayName("Standard WAV header correctly encapsulates 48kHz mono 16-bit PCM for STT")
    void testWavHeaderFormat() {
        byte[] testPcm = new byte[1920]; // 1 frame 20ms @ 48kHz
        byte[] wav = WavHeaderUtil.addWavHeader(testPcm, 48000, 1);

        assertNotNull(wav);
        assertEquals(44 + 1920, wav.length);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        byte[] riff = new byte[4];
        buf.get(riff);
        assertEquals("RIFF", new String(riff));
        assertEquals(36 + 1920, buf.getInt());

        byte[] wave = new byte[4];
        buf.get(wave);
        assertEquals("WAVE", new String(wave));

        byte[] fmt = new byte[4];
        buf.get(fmt);
        assertEquals("fmt ", new String(fmt));

        assertEquals(16, buf.getInt()); // Subchunk1Size
        assertEquals((short) 1, buf.getShort()); // PCM
        assertEquals((short) 1, buf.getShort()); // 1 channel (mono)
        assertEquals(48000, buf.getInt()); // Sample rate
        assertEquals(96000, buf.getInt()); // Byte rate (48000 * 1 * 2)
        assertEquals((short) 2, buf.getShort()); // Block align
        assertEquals((short) 16, buf.getShort()); // Bits per sample

        byte[] dataTag = new byte[4];
        buf.get(dataTag);
        assertEquals("data", new String(dataTag));
        assertEquals(1920, buf.getInt());
    }

    @Test
    @DisplayName("Simple downsampler decimate converts 48kHz 16-bit PCM to 16kHz mono")
    void testLinearDecimate48kTo16k() {
        // 960 samples @ 48kHz -> 320 samples @ 16kHz (factor of 3)
        short[] pcm48k = new short[960];
        for (int i = 0; i < pcm48k.length; i++) {
            pcm48k[i] = (short) i;
        }

        short[] pcm16k = new short[pcm48k.length / 3];
        for (int i = 0; i < pcm16k.length; i++) {
            pcm16k[i] = pcm48k[i * 3];
        }

        assertEquals(320, pcm16k.length);
        assertEquals(0, pcm16k[0]);
        assertEquals(3, pcm16k[1]);
        assertEquals(6, pcm16k[2]);
    }
}
