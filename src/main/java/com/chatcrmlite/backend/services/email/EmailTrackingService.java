package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.utils.EmailTrackingUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EmailTrackingService {

    private final EmailTrackedLinkRepository trackedLinkRepository;
    private final EmailCampaignRecipientRepository recipientRepository;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String baseUrl; // This should ideally be the backend tracking domain like track.gyanvaniai.online, but we use api domain

    public String generateTrackingToken() {
        return EmailTrackingUtils.generateToken();
    }

    @Transactional
    public String rewriteLinks(String htmlBody, UUID tenantId, UUID campaignId, String trackingToken) {
        if (htmlBody == null) return null;

        // Simple regex to find href="..."
        String hrefRegex = "href\\s*=\\s*\"([^\"]+)\"";
        Pattern pattern = Pattern.compile(hrefRegex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(htmlBody);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String originalUrl = matcher.group(1);
            
            // Skip mailto:, tel:, and already tracked links
            if (originalUrl.startsWith("mailto:") || originalUrl.startsWith("tel:") || originalUrl.startsWith("#")) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("href=\"" + originalUrl + "\""));
                continue;
            }

            // Create tracked link record
            String linkToken = EmailTrackingUtils.generateToken();
            EmailTrackedLink trackedLink = EmailTrackedLink.builder()
                    .tenantId(tenantId)
                    .campaignId(campaignId)
                    .linkToken(linkToken)
                    .destinationUrl(originalUrl)
                    .build();
            trackedLinkRepository.save(trackedLink);

            // Replace with tracking URL
            // e.g. /t/c/{trackingToken}?l={linkToken}
            // Ideally we prepend the public API URL
            // String trackingUrl = baseUrl + "/api/v1/t/c/" + trackingToken + "?l=" + linkToken;
            // For now, let's just make it relative to the API domain that serves the frontend/backend
            String trackingUrl = baseUrl + "/api/v1/t/c/" + trackingToken + "?l=" + linkToken;

            matcher.appendReplacement(sb, Matcher.quoteReplacement("href=\"" + trackingUrl + "\""));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public String injectTrackingPixel(String htmlBody, String trackingToken) {
        if (htmlBody == null) return null;
        String pixelUrl = baseUrl + "/api/v1/t/o/" + trackingToken + ".png";
        String pixelImg = "<img src=\"" + pixelUrl + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none;\" />";

        // Insert just before </body> if present, else append
        if (htmlBody.toLowerCase().contains("</body>")) {
            return htmlBody.replaceAll("(?i)</body>", pixelImg + "</body>");
        } else {
            return htmlBody + pixelImg;
        }
    }

    public String getUnsubscribeUrl(String trackingToken) {
        return baseUrl + "/api/v1/u/" + trackingToken;
    }

    public String appendUnsubscribeFooter(String htmlBody, String trackingToken) {
        if (htmlBody == null) return null;
        String unsubUrl = getUnsubscribeUrl(trackingToken);
        String footer = "<br><br><div style=\"text-align:center;font-size:12px;color:#999;\">" +
                        "If you no longer wish to receive these emails, you can " +
                        "<a href=\"" + unsubUrl + "\" style=\"color:#666;text-decoration:underline;\">unsubscribe here</a>." +
                        "</div>";

        if (htmlBody.toLowerCase().contains("</body>")) {
            return htmlBody.replaceAll("(?i)</body>", footer + "</body>");
        } else {
            return htmlBody + footer;
        }
    }
}
