package com.chatcrmlite.backend.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    // In-memory storage for OTPs (Email -> OTP)
    private final Map<String, String> otpStorage = new ConcurrentHashMap<>();

    public void generateAndSendOtp(String toEmail) {
        String otp = String.format("%06d", new Random().nextInt(999999));
        otpStorage.put(toEmail, otp);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("ewardmacllum@gmail.com");
        message.setTo(toEmail);
        message.setSubject("Your ChatCRM Lite Verification Code");
        message.setText("Dear User,\n\nYour OTP for logging into ChatCRM Lite is: " + otp + 
                "\n\nThis code will expire in 10 minutes.\n\nBest regards,\nChatCRM Lite Team");
        try {
            mailSender.send(message);
            log.info("OTP email sent successfully to: {}", toEmail);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to send OTP email to {}. Error: {}", toEmail, e.getMessage());
            throw new RuntimeException("Could not send verification email. Please try again later.");
        }
    }

    public boolean verifyOtp(String email, String otp) {
        if (otpStorage.containsKey(email) && otpStorage.get(email).equals(otp)) {
            otpStorage.remove(email); // One-time use
            return true;
        }
        return false;
    }
}
