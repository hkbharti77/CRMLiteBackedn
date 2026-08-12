package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.clients.MetaOnboardingClient;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.security.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Meta WhatsApp Gateway Controller
 *
 * Orchestrates Meta Tech Provider Embedded Signup (Coexistence) from the backend.
 * Provides:
 * 1. Gateway session initialization (/session)
 * 2. Server-rendered popup launcher (/launch)
 * 3. Token exchange & auto-provisioning (/exchange)
 * 4. Integration status monitoring (/status)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/meta/gateway")
public class MetaGatewayController {

    @Autowired
    private MetaOnboardingClient metaOnboardingClient;

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${meta.app.id:1573307991099476}")
    private String metaAppId;

    @Value("${meta.config.id:1052344107323702}")
    private String metaConfigId;

    @Value("${meta.app-secret:}")
    private String metaAppSecret;

    @Value("${app.public.url:http://localhost:8080}")
    private String publicAppUrl;

    /**
     * Resolves authenticated user from SecurityContext or Token query param.
     */
    private User resolveUser(String tokenParam) {
        // Try SecurityContext first
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String) {
            String email = (String) auth.getPrincipal();
            if (StringUtils.hasText(email) && !"anonymousUser".equalsIgnoreCase(email)) {
                return userRepository.findByEmail(email).orElse(null);
            }
        }

        // Fallback to token parameter
        if (StringUtils.hasText(tokenParam)) {
            try {
                if (jwtUtils.validateJwtToken(tokenParam)) {
                    String email = jwtUtils.getEmailFromJwtToken(tokenParam);
                    if (StringUtils.hasText(email)) {
                        return userRepository.findByEmail(email).orElse(null);
                    }
                }
            } catch (Exception e) {
                log.warn("[MetaGateway] Invalid token param provided: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Step 1: Session Initiation
     * Returns Meta Gateway session configuration and security state token.
     */
    @GetMapping("/session")
    public ResponseEntity<?> getGatewaySession(@RequestParam(required = false) String token) {
        User user = resolveUser(token);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String stateToken = UUID.randomUUID().toString();
        String verifyToken = "crm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("appId", metaAppId);
        sessionData.put("configId", metaConfigId);
        sessionData.put("state", stateToken);
        sessionData.put("verifyToken", verifyToken);
        sessionData.put("coexistenceEnabled", true);
        sessionData.put("sessionInfoVersion", "3");
        sessionData.put("userEmail", user.getEmail());
        sessionData.put("tenantId", user.getTenant() != null ? user.getTenant().getId().toString() : user.getId().toString());

        return ResponseEntity.ok(sessionData);
    }

    /**
     * Step 2: Server-Rendered Gateway Launcher HTML Page
     * Serves an interactive, branded popup window that loads the Meta JS SDK,
     * triggers Facebook Embedded Signup with Coexistence, performs the exchange,
     * and sends window.opener.postMessage back to the frontend.
     */
    @GetMapping(value = "/launch", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderGatewayLauncher(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String origin) {

        String safeAppId = StringUtils.hasText(metaAppId) ? metaAppId : "1573307991099476";
        String safeConfigId = StringUtils.hasText(metaConfigId) ? metaConfigId : "1052344107323702";
        String safeToken = token != null ? token : "";
        String initialThemeClass = "dark".equalsIgnoreCase(theme) ? "dark" : ("light".equalsIgnoreCase(theme) ? "light" : "auto");

        String html = """
        <!DOCTYPE html>
        <html lang="en" class="__THEME_CLASS__">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>Meta WhatsApp Coexistence Gateway | GyanVaniAi Connect</title>
          <style>
            :root {
              --bg-body: #F8FAFC;
              --text-primary: #0F172A;
              --text-secondary: #475569;
              --bg-card: #FFFFFF;
              --border-card: #E2E8F0;
              --shadow-card: 0 20px 40px rgba(0,0,0,0.08);
              --status-load-bg: #EFF6FF;
              --status-load-border: #BFDBFE;
              --status-load-text: #1D4ED8;
              --status-succ-bg: #ECFDF5;
              --status-succ-border: #A7F3D0;
              --status-succ-text: #047857;
              --status-err-bg: #FEF2F2;
              --status-err-border: #FECACA;
              --status-err-text: #B91C1C;
              --theme-btn-bg: #F1F5F9;
              --theme-btn-border: #CBD5E1;
              --theme-btn-text: #475569;
            }

            html.dark {
              --bg-body: #0B1120;
              --text-primary: #F9FAFB;
              --text-secondary: #9CA3AF;
              --bg-card: #111827;
              --border-card: #1F2937;
              --shadow-card: 0 20px 40px rgba(0,0,0,0.5);
              --status-load-bg: rgba(59, 130, 246, 0.1);
              --status-load-border: rgba(59, 130, 246, 0.3);
              --status-load-text: #60A5FA;
              --status-succ-bg: rgba(16, 185, 129, 0.1);
              --status-succ-border: rgba(16, 185, 129, 0.3);
              --status-succ-text: #34D399;
              --status-err-bg: rgba(239, 68, 68, 0.1);
              --status-err-border: rgba(239, 68, 68, 0.3);
              --status-err-text: #F87171;
              --theme-btn-bg: #1F2937;
              --theme-btn-border: #374151;
              --theme-btn-text: #D1D5DB;
            }

            @media (prefers-color-scheme: dark) {
              html.auto {
                --bg-body: #0B1120;
                --text-primary: #F9FAFB;
                --text-secondary: #9CA3AF;
                --bg-card: #111827;
                --border-card: #1F2937;
                --shadow-card: 0 20px 40px rgba(0,0,0,0.5);
                --status-load-bg: rgba(59, 130, 246, 0.1);
                --status-load-border: rgba(59, 130, 246, 0.3);
                --status-load-text: #60A5FA;
                --status-succ-bg: rgba(16, 185, 129, 0.1);
                --status-succ-border: rgba(16, 185, 129, 0.3);
                --status-succ-text: #34D399;
                --status-err-bg: rgba(239, 68, 68, 0.1);
                --status-err-border: rgba(239, 68, 68, 0.3);
                --status-err-text: #F87171;
                --theme-btn-bg: #1F2937;
                --theme-btn-border: #374151;
                --theme-btn-text: #D1D5DB;
              }
            }

            * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
            body { background: var(--bg-body); color: var(--text-primary); display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 20px; transition: background 0.3s ease, color 0.3s ease; }
            .card { position: relative; background: var(--bg-card); border: 1px solid var(--border-card); border-radius: 24px; padding: 36px 32px; max-width: 440px; width: 100%; text-align: center; box-shadow: var(--shadow-card); transition: all 0.3s ease; }
            .theme-toggle { position: absolute; top: 18px; right: 18px; background: var(--theme-btn-bg); border: 1px solid var(--theme-btn-border); color: var(--theme-btn-text); border-radius: 10px; padding: 6px 10px; font-size: 11px; font-weight: 600; cursor: pointer; display: flex; align-items: center; gap: 4px; transition: all 0.2s ease; }
            .theme-toggle:hover { opacity: 0.85; }
            .logo-wrap { width: 60px; height: 60px; margin: 0 auto 18px; background: rgba(24, 119, 242, 0.1); border-radius: 18px; display: flex; align-items: center; justify-content: center; color: #1877F2; }
            .logo-wrap svg { width: 34px; height: 34px; fill: currentColor; }
            h1 { font-size: 21px; font-weight: 800; color: var(--text-primary); margin-bottom: 8px; }
            p { font-size: 13px; color: var(--text-secondary); line-height: 1.5; margin-bottom: 24px; }
            .btn-fb { background: #1877F2; color: #fff; border: none; padding: 14px 24px; border-radius: 14px; font-size: 14px; font-weight: 700; cursor: pointer; width: 100%; display: flex; align-items: center; justify-content: center; gap: 10px; transition: all 0.2s ease; box-shadow: 0 4px 12px rgba(24, 119, 242, 0.3); }
            .btn-fb:hover { background: #166fe5; transform: translateY(-1px); box-shadow: 0 6px 16px rgba(24, 119, 242, 0.4); }
            .btn-fb:disabled { opacity: 0.6; cursor: not-allowed; transform: none; box-shadow: none; }
            .status-box { margin-top: 20px; padding: 14px; border-radius: 12px; font-size: 12px; display: none; text-align: left; line-height: 1.4; }
            .status-loading { background: var(--status-load-bg); border: 1px solid var(--status-load-border); color: var(--status-load-text); display: block; }
            .status-success { background: var(--status-succ-bg); border: 1px solid var(--status-succ-border); color: var(--status-succ-text); display: block; }
            .status-error { background: var(--status-err-bg); border: 1px solid var(--status-err-border); color: var(--status-err-text); display: block; }
            .badge { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: #10B981; margin-top: 20px; font-weight: 600; }
            .spinner { width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.3); border-top-color: #fff; border-radius: 50%; animation: spin 0.8s linear infinite; display: inline-block; }
            @keyframes spin { to { transform: rotate(360deg); } }
          </style>
        </head>
        <body>
          <div class="card">
            <button class="theme-toggle" onclick="toggleTheme()" id="themeBtn">
              <span id="themeIcon">🌓</span> <span id="themeLabel">Theme</span>
            </button>

            <div class="logo-wrap">
              <svg viewBox="0 0 24 24"><path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/></svg>
            </div>
            <h1>Meta WhatsApp Gateway</h1>
            <p>Connect your WhatsApp Business number with <strong>Coexistence Mode</strong>. Keep your mobile WhatsApp app active while automating live chats & broadcasts via GyanVaniAi Connect.</p>

            <button id="connectBtn" class="btn-fb" onclick="launchFacebookLogin()">
              <svg style="width:18px;height:18px;fill:currentColor" viewBox="0 0 24 24"><path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/></svg>
              <span>Connect with Facebook</span>
            </button>

            <div id="statusBox" class="status-box"></div>

            <div class="badge">
              <span>●</span> Official Meta Verified Tech Provider Gateway
            </div>
          </div>

          <script>
            function toggleTheme() {
              const html = document.documentElement;
              if (html.classList.contains('dark')) {
                html.classList.remove('dark', 'auto');
                html.classList.add('light');
                document.getElementById('themeIcon').textContent = '☀️';
              } else {
                html.classList.remove('light', 'auto');
                html.classList.add('dark');
                document.getElementById('themeIcon').textContent = '🌙';
              }
            }

            const APP_ID = '__APP_ID__';
            const CONFIG_ID = '__CONFIG_ID__';
            const TOKEN = '__TOKEN__';

            window.fbAsyncInit = function() {
              FB.init({
                appId: APP_ID,
                cookie: true,
                xfbml: true,
                version: 'v20.0'
              });
            };

            (function(d, s, id) {
              var js, fjs = d.getElementsByTagName(s)[0];
              if (d.getElementById(id)) return;
              js = d.createElement(s); js.id = id;
              js.src = "https://connect.facebook.net/en_US/sdk.js";
              fjs.parentNode.insertBefore(js, fjs);
            }(document, 'script', 'facebook-jssdk'));

            function setStatus(type, html) {
              const box = document.getElementById('statusBox');
              box.className = 'status-box status-' + type;
              box.innerHTML = html;
              box.style.display = 'block';
            }

            function launchFacebookLogin() {
              const btn = document.getElementById('connectBtn');
              btn.disabled = true;
              btn.innerHTML = '<span class="spinner"></span> <span>Connecting to Meta...</span>';
              setStatus('loading', 'Opening official Meta authorization dialog...');

              if (!window.FB) {
                setStatus('error', 'Facebook SDK could not be loaded. Please ensure popup / script permissions are allowed.');
                btn.disabled = false;
                btn.innerHTML = 'Retry Connection';
                return;
              }

              FB.login(function(response) {
                if (response.authResponse && response.authResponse.code) {
                  const oauthCode = response.authResponse.code;
                  setStatus('loading', 'Exchanging authorization code with backend & registering webhooks...');

                  fetch('/api/v1/integrations/meta/gateway/exchange', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/json',
                      ...(TOKEN ? { 'Authorization': 'Bearer ' + TOKEN } : {})
                    },
                    body: JSON.stringify({ code: oauthCode })
                  })
                  .then(res => res.json())
                  .then(data => {
                    if (data.error) {
                      setStatus('error', 'Gateway Exchange Error: ' + data.error);
                      btn.disabled = false;
                      btn.innerHTML = 'Retry Connection';
                    } else {
                      setStatus('success', '✓ WhatsApp Coexistence Connected Successfully! Redirecting back to CRM...');
                      if (window.opener) {
                        window.opener.postMessage({
                          type: 'META_WHATSAPP_CONNECTED',
                          success: true,
                          data: data
                        }, '*');
                      }
                      setTimeout(() => {
                        window.close();
                      }, 1500);
                    }
                  })
                  .catch(err => {
                    setStatus('error', 'Network error contacting backend: ' + err.message);
                    btn.disabled = false;
                    btn.innerHTML = 'Retry Connection';
                  });
                } else {
                  setStatus('error', 'Meta login was cancelled or authorization was denied.');
                  btn.disabled = false;
                  btn.innerHTML = 'Connect with Facebook';
                }
              }, {
                config_id: CONFIG_ID,
                response_type: 'code',
                override_default_response_type: true,
                extras: {
                  setup: {},
                  featureType: '',
                  sessionInfoVersion: '3'
                }
              });
            }
          </script>
        </body>
        </html>
        """
        .replace("__THEME_CLASS__", initialThemeClass)
        .replace("__APP_ID__", safeAppId)
        .replace("__CONFIG_ID__", safeConfigId)
        .replace("__TOKEN__", safeToken);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * Step 3: Gateway Token Exchange & Auto Webhook Subscription
     */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchangeGatewayToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> payload) {

        String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
        User user = resolveUser(token);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required to link WhatsApp account"));
        }

        String code = payload.get("code");
        if (!StringUtils.hasText(code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "OAuth authorization code is required"));
        }

        try {
            log.info("[MetaGateway] Exchanging OAuth code for user={}", user.getEmail());

            // 1. Exchange for Long Lived System Token
            String longLivedToken = metaOnboardingClient.exchangeForLongLivedToken(code);

            // 2. Debug Token & Fetch IDs
            Map<String, Object> debugData = metaOnboardingClient.debugToken(longLivedToken);
            String businessId = (String) debugData.get("businessId");
            LocalDateTime tokenExpiry = (LocalDateTime) debugData.get("tokenExpiry");

            // 3. Fetch WABA ID
            String wabaId = (String) debugData.get("wabaId");
            if (!StringUtils.hasText(wabaId) && StringUtils.hasText(businessId)) {
                wabaId = metaOnboardingClient.fetchWabaId(businessId, longLivedToken);
            }

            // 4. Fetch Phone Number Details
            Map<String, String> phoneDetails = new HashMap<>();
            if (StringUtils.hasText(wabaId)) {
                try {
                    phoneDetails = metaOnboardingClient.fetchPhoneNumberDetails(wabaId, longLivedToken);
                } catch (Exception e) {
                    log.warn("[MetaGateway] Could not fetch phone details immediately: {}", e.getMessage());
                }
            }

            // 5. Auto-subscribe WABA to our App Webhooks
            if (StringUtils.hasText(wabaId) && StringUtils.hasText(longLivedToken)) {
                try {
                    String subscribeUrl = String.format("https://graph.facebook.com/v19.0/%s/subscribed_apps?access_token=%s", wabaId, longLivedToken);
                    restTemplate.postForEntity(subscribeUrl, null, String.class);
                    log.info("[MetaGateway] Successfully auto-subscribed WABA {} to webhooks", wabaId);
                } catch (Exception e) {
                    log.warn("[MetaGateway] Auto-subscription warning: {}", e.getMessage());
                }
            }

            // 6. Save or Update WhatsAppConfig
            WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId())
                    .orElse(new WhatsAppConfig());

            config.setUser(user);
            config.setConnectionType("EMBEDDED_SIGNUP_COEXISTENCE");
            config.setAccessToken(longLivedToken);
            config.setTokenExpiry(tokenExpiry);
            config.setBusinessId(businessId);
            config.setWabaId(wabaId);

            if (phoneDetails.containsKey("phoneNumberId")) {
                config.setPhoneNumberId(phoneDetails.get("phoneNumberId"));
            }
            if (phoneDetails.containsKey("displayPhoneNumber")) {
                config.setDisplayPhoneNumber(phoneDetails.get("displayPhoneNumber"));
            }
            if (phoneDetails.containsKey("verifiedName")) {
                config.setVerifiedName(phoneDetails.get("verifiedName"));
            }
            if (phoneDetails.containsKey("qualityRating")) {
                config.setQualityRating(phoneDetails.get("qualityRating"));
            }

            config.setVerificationStatus("VERIFIED");
            config.setAccountStatus("ACTIVE");

            if (!StringUtils.hasText(config.getVerifyToken())) {
                config.setVerifyToken("crm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }

            whatsappConfigRepository.save(config);
            log.info("[MetaGateway] WhatsApp Coexistence configuration successfully saved for user={}", user.getEmail());

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "WhatsApp Coexistence Embedded Signup Connected Successfully");
            resp.put("phoneNumberId", config.getPhoneNumberId());
            resp.put("displayPhoneNumber", config.getDisplayPhoneNumber());
            resp.put("verifiedName", config.getVerifiedName());
            resp.put("wabaId", config.getWabaId());
            resp.put("connectionType", "EMBEDDED_SIGNUP_COEXISTENCE");

            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("[MetaGateway] Exchange failed for user={}", user.getEmail(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Integration exchange failed: " + e.getMessage()));
        }
    }

    /**
     * Step 4: Integration Status Check
     */
    @GetMapping("/status")
    public ResponseEntity<?> getIntegrationStatus(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
        User user = resolveUser(token);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId()).orElse(null);
        if (config == null || !StringUtils.hasText(config.getAccessToken())) {
            return ResponseEntity.ok(Map.of("connected", false));
        }

        Map<String, Object> status = new HashMap<>();
        status.put("connected", true);
        status.put("connectionType", config.getConnectionType());
        status.put("phoneNumberId", config.getPhoneNumberId());
        status.put("displayPhoneNumber", config.getDisplayPhoneNumber());
        status.put("verifiedName", config.getVerifiedName());
        status.put("qualityRating", config.getQualityRating());
        status.put("wabaId", config.getWabaId());
        status.put("verificationStatus", config.getVerificationStatus());

        return ResponseEntity.ok(status);
    }
}
