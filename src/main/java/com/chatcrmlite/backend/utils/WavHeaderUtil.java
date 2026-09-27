package com.chatcrmlite.backend.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavHeaderUtil {
    public static byte[] addWavHeader(byte[] pcmData, int sampleRate, int channels) {
        int byteRate = sampleRate * channels * 2;
        int dataSize = pcmData.length;
        byte[] wav = new byte[44 + dataSize];
        
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes());
        buffer.put("fmt ".getBytes());
        buffer.putInt(16); // Subchunk1Size
        buffer.putShort((short) 1); // AudioFormat (PCM)
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) (channels * 2)); // BlockAlign
        buffer.putShort((short) 16); // BitsPerSample
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        buffer.put(pcmData);
        
        return wav;
    }
}

