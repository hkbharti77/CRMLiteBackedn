package com.chatcrmlite.backend;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.util.*;

public class TestSarvam {
    public static void main(String[] args) throws Exception {
        String key = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("d:/xyzzz/MinorProject/CRMLiteBackedn/.env"))
            .stream().filter(l -> l.startsWith("SARVAM_API_KEY=")).map(l -> l.split("=")[1].trim()).findFirst().get();

        RestTemplate restTemplate = new RestTemplateBuilder().build();
        HttpHeaders headers = new HttpHeaders();
        headers.set("api-subscription-key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
        headers.set("Accept-Encoding", "identity");

        Map<String, Object> body = new HashMap<>();
        body.put("text", "Hello world");
        body.put("target_language_code", "hi-IN");
        body.put("speaker", "simran");
        body.put("model", "bulbul:v3");
        body.put("pace", 1.0);
        body.put("speech_sample_rate", 22050);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange("https://api.sarvam.ai/text-to-speech", HttpMethod.POST, requestEntity, byte[].class);
            byte[] bodyBytes = response.getBody();
            System.out.println("Status: " + response.getStatusCode());
            System.out.println("Bytes length: " + bodyBytes.length);
            System.out.print("First 16 bytes: ");
            for(int i = 0; i < Math.min(16, bodyBytes.length); i++) {
                System.out.printf("%02X ", bodyBytes[i]);
            }
            System.out.println();
            
            // Handle GZIP
            if (bodyBytes.length > 2 && bodyBytes[0] == (byte) 0x1F && bodyBytes[1] == (byte) 0x8B) {
                System.out.println("GZIP DETECTED!");
                try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bodyBytes));
                     java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    bodyBytes = baos.toByteArray();
                }
            } else {
                System.out.println("NOT GZIP.");
            }

            String str = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("String length: " + str.length());
            System.out.println("Starts with: " + str.substring(0, Math.min(50, str.length())));
            System.out.println("First 5 chars as ints:");
            for(int i = 0; i < 5; i++) {
                System.out.println((int)str.charAt(i));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
