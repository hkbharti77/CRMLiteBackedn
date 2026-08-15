package com.chatcrmlite.backend.services.email.providers;

import com.chatcrmlite.backend.services.email.EmailRequest;
import com.chatcrmlite.backend.services.email.EmailSenderProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class SmtpProvider implements EmailSenderProvider {
    
    private final String credentialsPayload;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public SmtpProvider(String credentialsPayload) {
        this.credentialsPayload = credentialsPayload;
    }

    private JavaMailSenderImpl createMailSender() throws Exception {
        Map<String, Object> creds = objectMapper.readValue(credentialsPayload, Map.class);
        String host = String.valueOf(creds.getOrDefault("host", "smtp.gmail.com")).trim();
        int port = 587;
        try {
            port = Integer.parseInt(String.valueOf(creds.getOrDefault("port", "587")).trim());
        } catch (Exception ignored) {}

        String username = String.valueOf(creds.getOrDefault("username", "")).trim();
        String password = String.valueOf(creds.getOrDefault("password", "")).trim();
        String encryption = String.valueOf(creds.getOrDefault("encryption", "TLS")).trim();

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");

        // Port 465 uses implicit SSL; Port 587 uses STARTTLS
        if (port == 465 || ("SSL".equalsIgnoreCase(encryption) && port != 587)) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        }

        return mailSender;
    }

    @Override
    public void sendTestEmail(String toEmail, String fromEmail) throws Exception {
        log.info("Sending SMTP Test Email to {} from {}", toEmail, fromEmail);
        JavaMailSenderImpl sender = createMailSender();

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(toEmail);
        helper.setSubject("CRM Lite - Test Email Connection Verified");
        helper.setText("<h3>Email Provider Verified</h3><p>Your custom SMTP provider settings are working properly and sending emails successfully!</p>", true);

        sender.send(message);
        log.info("Successfully delivered SMTP Test Email to {}", toEmail);
    }

    @Override
    public void sendBatch(List<EmailRequest> requests, String fromEmail) throws Exception {
        log.info("Sending SMTP Batch of size {}", requests.size());
        JavaMailSenderImpl sender = createMailSender();

        for (EmailRequest req : requests) {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(req.getToEmail());
                helper.setSubject(req.getSubject());
                helper.setText(req.getHtmlBody(), true);

                sender.send(message);
                log.info("Successfully delivered SMTP email to {}", req.getToEmail());
            } catch (Exception e) {
                log.error("Failed to deliver SMTP email to {}", req.getToEmail(), e);
            }
        }
    }
}
