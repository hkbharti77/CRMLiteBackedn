package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TokenRefreshService {

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    /**
     * Runs daily at midnight to check for tokens expiring within 7 days.
     * In a production BSP, this would trigger an email or in-app notification to the tenant owner.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void checkExpiringTokens() {
        log.info("Starting daily check for expiring Meta tokens...");
        
        LocalDateTime sevenDaysFromNow = LocalDateTime.now().plusDays(7);
        
        List<WhatsAppConfig> allConfigs = whatsappConfigRepository.findAll();
        
        List<WhatsAppConfig> expiringConfigs = allConfigs.stream()
                .filter(c -> c.getTokenExpiry() != null && c.getTokenExpiry().isBefore(sevenDaysFromNow))
                .collect(Collectors.toList());

        for (WhatsAppConfig config : expiringConfigs) {
            log.warn("⚠️ [BSP Compliance] WhatsApp token for tenant {} (WABA: {}) is expiring on {}. Please notify owner.",
                    config.getTenant().getId(), config.getWabaId(), config.getTokenExpiry());
                    
            // TODO: Integrate with EmailService or NotificationService here to alert the client
            // emailService.sendExpirationWarning(config.getUser().getEmail(), config.getTokenExpiry());
        }

        log.info("Finished token expiration check. Found {} expiring tokens.", expiringConfigs.size());
    }
}
