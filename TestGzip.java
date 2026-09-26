public class TestGzip {
    public static void main(String[] args) {
        byte[] bodyBytes = new byte[] { (byte)0x1F, (byte)0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xFF };
        if (bodyBytes.length > 2 && bodyBytes[0] == (byte) 0x1F && bodyBytes[1] == (byte) 0x8B) {
            System.out.println("GZIP DETECTED!");
        } else {
            System.out.println("NO GZIP!");
        }
    }
}
