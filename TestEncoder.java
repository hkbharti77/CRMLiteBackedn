import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TestEncoder {
    public static void main(String[] args) throws Exception {
        String filename = args.length > 0 ? args[0] : "test.wav";
        byte[] audioBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filename));
        System.out.println("Length: " + audioBytes.length);
        int inputSampleRate = 22050;
        int dataOffset = 0;
        int dataLen = audioBytes.length;

        if (audioBytes.length > 12 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
            ByteBuffer header = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);
            int offset = 12;
            while (offset + 8 <= audioBytes.length) {
                String chunkId = new String(audioBytes, offset, 4);
                int chunkSize = header.getInt(offset + 4);
                System.out.println("Chunk: " + chunkId + " Size: " + chunkSize);
                if ("fmt ".equals(chunkId)) {
                    inputSampleRate = header.getInt(offset + 12);
                } else if ("data".equals(chunkId)) {
                    dataOffset = offset + 8;
                    dataLen = chunkSize;
                    break;
                }
                offset += 8 + chunkSize;
            }
        }
        System.out.println("dataOffset: " + dataOffset + " dataLen: " + dataLen);
        if (dataLen < 0 || dataLen > audioBytes.length) {
             System.out.println("WARNING: bad dataLen " + dataLen + ", clamping to " + (audioBytes.length - dataOffset));
             dataLen = audioBytes.length - dataOffset;
        }
        short[] inputSamples = new short[dataLen / 2];
        System.out.println("Success! numSamples: " + inputSamples.length);
    }
}
