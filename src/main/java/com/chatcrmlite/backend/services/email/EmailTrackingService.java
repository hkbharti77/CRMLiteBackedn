package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.utils.EmailTrackingUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EmailTrackingService {

    private final EmailTrackedLinkRepository trackedLinkRepository;
    private final EmailCampaignRecipientRepository recipientRepository;

    @Value("${app.frontend.url:http://localhost:5174}")
    private String baseUrl;

    public String generateTrackingToken() {
        return EmailTrackingUtils.generateToken();
    }

    public String sanitizeHtml(String htmlBody) {
        if (htmlBody == null) return null;
        
        // Strip <script> and <iframe> elements and javascript: URIs
        String cleaned = htmlBody.replaceAll("(?i)<script[\\s\\S]*?>[\\s\\S]*?</script>", "")
                                 .replaceAll("(?i)<iframe[\\s\\S]*?>[\\s\\S]*?</iframe>", "")
                                 .replaceAll("(?i)href\\s*=\\s*\"javascript:[^\"]*\"", "href=\"#\"")
                                 .replaceAll("(?i)href\\s*=\\s*'javascript:[^']*'", "href=\"#\"");
        return cleaned;
    }

    @Transactional
    public String rewriteLinks(String htmlBody, UUID tenantId, UUID campaignId, String trackingToken) {
        if (htmlBody == null) return null;

        String sanitized = sanitizeHtml(htmlBody);

        // Regex matching href="..." or href='...'
        String hrefRegex = "href\\s*=\\s*([\"'])([^\"']+)\\1";
        Pattern pattern = Pattern.compile(hrefRegex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sanitized);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String quote = matcher.group(1);
            String originalUrl = matcher.group(2);
            
            String lowerUrl = originalUrl.toLowerCase();
            // Skip mailto:, tel:, fragment links, javascript:, or existing tracking/unsubscribe links
            if (lowerUrl.startsWith("mailto:") || lowerUrl.startsWith("tel:") || 
                lowerUrl.startsWith("#") || lowerUrl.startsWith("javascript:") ||
                lowerUrl.contains("/api/v1/u/")) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("href=" + quote + originalUrl + quote));
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

            // Single-token tracking URL
            String trackingUrl = baseUrl + "/api/v1/t/c/" + linkToken;

            matcher.appendReplacement(sb, Matcher.quoteReplacement("href=" + quote + trackingUrl + quote));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    public String injectTrackingPixel(String htmlBody, String trackingToken) {
        if (htmlBody == null) return null;
        String pixelUrl = baseUrl + "/api/v1/t/o/" + trackingToken + ".png";
        String pixelImg = "<img src=\"" + pixelUrl + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none;\" />";

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

    public Map<String, String> getUnsubscribeHeaders(String trackingToken) {
        Map<String, String> headers = new HashMap<>();
        String unsubUrl = getUnsubscribeUrl(trackingToken);
        headers.put("List-Unsubscribe", "<" + unsubUrl + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        return headers;
    }
}
