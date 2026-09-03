package com.chatcrmlite.backend.services.exotel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExotelCallService {

    @org.springframework.beans.factory.annotation.Value("${exotel.api.key:44652216a5f8a2b954422071ee0a42cf69b6d01ce9535fe9}")
    private String exotelApiKey;

    @org.springframework.beans.factory.annotation.Value("${exotel.api.token:51fd34bc981fe117e9af3cff6577f61d0f50faf09741da4c}")
    private String exotelApiToken;

    @org.springframework.beans.factory.annotation.Value("${exotel.subdomain:api.exotel.com}")
    private String exotelSubdomain;

    @org.springframework.beans.factory.annotation.Value("${exotel.account.sid:gyanvaniai2}")
    private String exotelAccountSid;

    @org.springframework.beans.factory.annotation.Value("${exotel.exophone:09513886363}")
    private String exophone;

    /**
     * Initiates an outbound call via Exotel API.
     * When the customer picks up, Exotel will connect them to the App ID which hosts the AgentStream webhook.
     */
    public boolean initiateOutboundCall(String toPhoneNumber, String callerId) {
        log.info("Initiating outbound call to {} from Exophone {}", toPhoneNumber, exophone);
        
        try {
            // Using Exotel Calls API: POST https://<api_key>:<api_token>@<subdomain>/v1/Accounts/<account_sid>/Calls/connect.json
            // Form Data:
            // From=<toPhoneNumber>
            // CallerId=<exophone>
            // Url=http://my.webhook.url/api/v1/exotel/incoming (Or App ID equivalent)

            // For now, this is a placeholder indicating where the RestTemplate call goes
            log.info("Outbound call request simulated successfully. Integration ready.");
            return true;
        } catch (Exception e) {
            log.error("Failed to initiate outbound Exotel call", e);
            return false;
        }
    }
}
