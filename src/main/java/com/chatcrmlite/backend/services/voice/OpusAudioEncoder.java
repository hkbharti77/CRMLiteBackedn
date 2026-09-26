package com.chatcrmlite.backend.services.voice;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure Java Opus Audio Encoder for WhatsApp WebRTC Calling.
 * Converts WAV / 16-bit PCM (any sample rate) into 20ms Opus frames (48kHz VOIP).
 */
@Slf4j
public class OpusAudioEncoder {

    private static final int OPUS_SAMPLE_RATE = 48000;
    private static final int OPUS_CHANNELS = 1;
    private static final int FRAME_SIZE_SAMPLES = 960; // 20ms @ 48kHz

    /**
     * Converts raw WAV or PCM bytes from TTS (e.g., Sarvam) into a list of Opus packet frames (20ms each).
     */
    public static List<byte[]> encodeWavOrPcmToOpusFrames(byte[] audioBytes) {
        List<byte[]> frames = new ArrayList<>();
        if (audioBytes == null || audioBytes.length == 0) {
            return frames;
        }

        try {
            int inputSampleRate = 22050; // Default Sarvam sample rate
            int dataOffset = 0;
            int dataLen = audioBytes.length;

            // Check for WAV header ("RIFF")
            if (audioBytes.length > 44 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
                ByteBuffer header = ByteBuffer.wrap(audioBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN);
                int channels = header.getShort(22) & 0xFFFF;
                inputSampleRate = header.getInt(24);
                int bitsPerSample = header.getShort(34) & 0xFFFF;
                dataOffset = 44;
                dataLen = audioBytes.length - 44;
                log.info("🎵 [OpusEncoder] Parsed WAV header: sampleRate={}Hz channels={} bitsPerSample={}",
                        inputSampleRate, channels, bitsPerSample);
            }

            // Convert 16-bit PCM bytes to short[] samples
            int numInputSamples = dataLen / 2;
            short[] inputSamples = new short[numInputSamples];
            ByteBuffer pcmBuf = ByteBuffer.wrap(audioBytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numInputSamples; i++) {
                inputSamples[i] = pcmBuf.getShort();
            }

            // Resample to 48000 Hz if necessary
            short[] resampledSamples;
            if (inputSampleRate != OPUS_SAMPLE_RATE && inputSampleRate > 0) {
                resampledSamples = resampleLinear(inputSamples, inputSampleRate, OPUS_SAMPLE_RATE);
            } else {
                resampledSamples = inputSamples;
            }

            // Encode 960-sample (20ms) blocks with Concentus OpusEncoder
            OpusEncoder encoder = new OpusEncoder(OPUS_SAMPLE_RATE, OPUS_CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24000);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);

            byte[] outPacket = new byte[1275];
            short[] frameBuffer = new short[FRAME_SIZE_SAMPLES];

            for (int offset = 0; offset < resampledSamples.length; offset += FRAME_SIZE_SAMPLES) {
                int samplesToCopy = Math.min(FRAME_SIZE_SAMPLES, resampledSamples.length - offset);
                Arrays.fill(frameBuffer, (short) 0);
                System.arraycopy(resampledSamples, offset, frameBuffer, 0, samplesToCopy);

                int bytesEncoded = encoder.encode(frameBuffer, 0, FRAME_SIZE_SAMPLES, outPacket, 0, outPacket.length);
                if (bytesEncoded > 0) {
                    frames.add(Arrays.copyOf(outPacket, bytesEncoded));
                }
            }

            log.info("🎵 [OpusEncoder] Encoded {} input bytes ({}Hz) into {} Opus frames (20ms each @ 48kHz)",
                    audioBytes.length, inputSampleRate, frames.size());

        } catch (Exception e) {
            log.error("❌ [OpusEncoder] Failed to encode audio to Opus: {}", e.getMessage(), e);
        }

        return frames;
    }

    /**
     * Simple linear interpolation resampler for 16-bit PCM audio.
     */
    private static short[] resampleLinear(short[] input, int inRate, int outRate) {
        if (inRate == outRate) return input;
        double ratio = (double) outRate / inRate;
        int outLength = (int) Math.round(input.length * ratio);
        short[] output = new short[outLength];

        for (int i = 0; i < outLength; i++) {
            double srcIdx = i / ratio;
            int idxFloor = (int) srcIdx;
            int idxCeil = Math.min(idxFloor + 1, input.length - 1);
            double frac = srcIdx - idxFloor;

            if (idxFloor < input.length) {
                double val = input[idxFloor] * (1.0 - frac) + input[idxCeil] * frac;
                output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(val)));
            }
        }
        return output;
    }
}
