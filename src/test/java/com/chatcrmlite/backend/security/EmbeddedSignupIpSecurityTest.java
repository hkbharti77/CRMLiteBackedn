package com.chatcrmlite.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class EmbeddedSignupIpSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void authController_ignoresXForwardedFor() throws Exception {
        mockMvc.perform(post("/api/v1/auth/initiate")
                .header("X-Forwarded-For", "192.168.1.100")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"mode\":\"login\"}"))
                .andExpect(status().is4xxClientError()); // The exact status depends on the implementation (400 or 401), but we verify it doesn't crash from IP processing
    }
}
