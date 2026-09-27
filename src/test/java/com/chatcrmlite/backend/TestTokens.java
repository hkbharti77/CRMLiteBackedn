package com.chatcrmlite.backend;

import com.chatcrmlite.backend.utils.EncryptionConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@SpringBootApplication
public class TestTokens implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EncryptionConverter encryptionConverter;

    public static void main(String[] args) {
        SpringApplication.run(TestTokens.class, args).close();
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("\n========== TESTING TOKENS ==========\n");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT tenant_id, phone_number_id, access_token, verified_name FROM whatsapp_configs");
        RestTemplate restTemplate = new RestTemplate();
        
        for (Map<String, Object> row : rows) {
            String tenantId = (String) row.get("tenant_id");
            String phoneId = (String) row.get("phone_number_id");
            String encToken = (String) row.get("access_token");
            String name = (String) row.get("verified_name");
            
            String token = encryptionConverter.convertToEntityAttribute(encToken);
            System.out.println("Tenant: " + tenantId + " (Name: " + name + ", Phone: " + phoneId + ")");
            
            if (token == null || token.isBlank()) {
                System.out.println("No token found.");
                continue;
            }
            
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(token);
                HttpEntity<String> entity = new HttpEntity<>(headers);
                String url = "https://graph.facebook.com/v19.0/" + phoneId;
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                System.out.println("STATUS: OK (Token is valid)");
            } catch (Exception e) {
                System.out.println("STATUS: FAILED -> " + e.getMessage());
            }
            System.out.println("------------------------------------");
        }
        System.out.println("\n====================================\n");
    }
}
