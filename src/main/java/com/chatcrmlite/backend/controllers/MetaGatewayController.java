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

    @Value("${FRONTEND_URL:${app.frontend.url:https://gyanvaniaiconnect.qivantaai.in}}")
    private String frontendUrl;

    private String extractTokenFromState(String state) {
        if (!StringUtils.hasText(state)) return null;
        String s = state.trim();
        if (s.startsWith("%7B") || s.startsWith("%7b")) {
            try {
                s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        if (s.startsWith("{") && s.endsWith("}")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                if (node.has("token")) {
                    return node.get("token").asText();
                }
            } catch (Exception ignored) {}
        }
        if (s.contains(":::")) {
            return s.split(":::")[0];
        }
        return s;
    }

    private String resolveRedirectUrl(String state) {
        String base = (frontendUrl != null && !frontendUrl.isBlank()) ? frontendUrl.replaceAll("/+$", "") : "https://gyanvaniaiconnect.qivantaai.in";
        if (StringUtils.hasText(state)) {
            String s = state.trim();
            if (s.startsWith("%7B") || s.startsWith("%7b")) {
                try {
                    s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            if (s.startsWith("{") && s.endsWith("}")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                    if (node.has("origin") && StringUtils.hasText(node.get("origin").asText())) {
                        base = node.get("origin").asText().replaceAll("/+$", "");
                    }
                } catch (Exception ignored) {}
            } else if (s.contains(":::")) {
                String[] parts = s.split(":::");
                if (parts.length > 1 && StringUtils.hasText(parts[1])) {
                    base = parts[1].replaceAll("/+$", "");
                }
            }
        }
        return base + "/meta-config";
    }

    /**
     * Resolves authenticated user from SecurityContext or Token query param.
     */
    private User resolveUser(String tokenParam) {
        String actualToken = extractTokenFromState(tokenParam);

        // Try SecurityContext first
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String) {
            String email = (String) auth.getPrincipal();
            if (StringUtils.hasText(email) && !"anonymousUser".equalsIgnoreCase(email)) {
                return userRepository.findByEmail(email).orElse(null);
            }
        }

        // Fallback to token parameter
        if (StringUtils.hasText(actualToken)) {
            try {
                if (jwtUtils.validateJwtToken(actualToken)) {
                    String email = jwtUtils.getEmailFromJwtToken(actualToken);
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

        String launcherBase = (publicAppUrl != null && !publicAppUrl.isBlank()) ? publicAppUrl.replaceAll("/+$", "") : "";
        String launcherUrl = launcherBase + "/api/v1/integrations/meta/gateway/launch";

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("appId", metaAppId);
        sessionData.put("configId", metaConfigId);
        sessionData.put("state", stateToken);
        sessionData.put("verifyToken", verifyToken);
        sessionData.put("coexistenceEnabled", true);
        sessionData.put("sessionInfoVersion", "3");
        sessionData.put("userEmail", user.getEmail());
        sessionData.put("tenantId", user.getTenant() != null ? user.getTenant().getId().toString() : user.getId().toString());
        sessionData.put("launcherUrl", launcherUrl);
        sessionData.put("publicAppUrl", publicAppUrl);

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
            const ORIGIN = '__ORIGIN__';
            const CALLBACK_URL = window.location.origin + '/api/v1/integrations/meta/gateway/callback';
            let sdkReady = false;

            window.fbAsyncInit = function() {
              try {
                FB.init({
                  appId: APP_ID,
                  cookie: true,
                  xfbml: false,
                  version: 'v20.0'
                });
                sdkReady = true;
                const btn = document.getElementById('connectBtn');
                btn.disabled = false;
                btn.innerHTML = '<svg style="width:18px;height:18px;fill:currentColor" viewBox="0 0 24 24"><path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/></svg> <span>Connect with Facebook</span>';
              } catch (e) {
                console.warn('FB.init error:', e);
              }
            };

            // Direct OAuth dialog fallback (Ad-block safe)
            function launchDirectOAuth() {
              const originUrl = ORIGIN || window.location.origin;
              const stateVal = TOKEN ? (TOKEN + ':::' + originUrl) : originUrl;
              const oauthUrl = 'https://www.facebook.com/v20.0/dialog/oauth?' + 
                'client_id=' + encodeURIComponent(APP_ID) + 
                '&redirect_uri=' + encodeURIComponent(CALLBACK_URL) + 
                '&config_id=' + encodeURIComponent(CONFIG_ID) + 
                '&response_type=code' + 
                '&state=' + encodeURIComponent(stateVal);
              setStatus('loading', 'Redirecting to Meta authorization dialog...');
              window.location.href = oauthUrl;
            }

            function setStatus(type, html) {
              const box = document.getElementById('statusBox');
              box.className = 'status-box status-' + type;
              box.innerHTML = html;
              box.style.display = 'block';
            }

            function launchFacebookLogin() {
              if (!sdkReady || typeof FB === 'undefined' || !FB.login) {
                launchDirectOAuth();
                return;
              }

              const btn = document.getElementById('connectBtn');
              btn.disabled = true;
              btn.innerHTML = '<span class="spinner"></span> <span>Connecting to Meta...</span>';
              setStatus('loading', 'Opening official Meta authorization dialog...');

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
                        try { window.close(); } catch(e) {}
                        const target = ORIGIN ? (ORIGIN + '/meta-config') : '/meta-config';
                        window.location.href = target;
                      }, 1500);
                    }
                  })
                  .catch(err => {
                    setStatus('error', 'Network error contacting backend: ' + err.message);
                    btn.disabled = false;
                    btn.innerHTML = 'Retry Connection';
                  });
                } else {
                  setStatus('error', 'Meta login was cancelled or authorization was denied.<br/><a href="#" onclick="launchDirectOAuth(); return false;" style="color:#1877F2;font-weight:bold;text-decoration:underline;margin-top:6px;display:inline-block;">👉 Or Click Here to Connect via Direct Meta Dialog</a>');
                  btn.disabled = false;
                  btn.innerHTML = 'Connect with Facebook';
                }
              }, {
                config_id: CONFIG_ID,
                response_type: 'code',
                override_default_response_type: true,
                extras: {
                  setup: {},
                  featureType: 'coexistence',
                  sessionInfoVersion: '3'
                }
              });
            }
          </script>
          <script async defer crossorigin="anonymous" src="https://connect.facebook.net/en_US/sdk.js"></script>
        </body>
        </html>
        """
        .replace("__THEME_CLASS__", initialThemeClass)
        .replace("__APP_ID__", safeAppId)
        .replace("__CONFIG_ID__", safeConfigId)
        .replace("__TOKEN__", safeToken)
        .replace("__ORIGIN__", origin != null ? origin : (frontendUrl != null ? frontendUrl : ""));

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

    /**
     * Step 5: Direct OAuth Redirect Callback Handler
     * Handles direct Meta OAuth redirect when JS SDK is blocked by browser ad-blocker.
     */
    @GetMapping(value = {"/callback", "/oauth/callback"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleDirectOAuthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {

        String targetUrl = resolveRedirectUrl(state);

        if (StringUtils.hasText(error) || !StringUtils.hasText(code)) {
            String errMsg = StringUtils.hasText(errorDescription) ? errorDescription : (error != null ? error : "Authorization was cancelled or denied");
            String errHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Connection Cancelled | GyanVaniAi</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 24px; background: #F8FAFC; color: #0F172A; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                .card { background: #fff; border: 1px solid #E2E8F0; border-radius: 24px; padding: 36px 28px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.06); }
                .icon { font-size: 44px; margin-bottom: 12px; }
                h2 { color: #DC2626; font-size: 20px; font-weight: 800; margin-bottom: 8px; }
                p { color: #64748B; font-size: 13px; line-height: 1.5; margin-bottom: 24px; word-break: break-word; }
                .btn { display: inline-flex; align-items: center; justify-content: center; background: #0F172A; color: #fff; text-decoration: none; font-weight: 700; font-size: 14px; padding: 13px 24px; border-radius: 12px; width: 100%; transition: background 0.2s; }
                .btn:hover { background: #1E293B; }
                .redirect-note { margin-top: 14px; font-size: 12px; color: #94A3B8; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">⚠️</div>
                <h2>Connection Cancelled</h2>
                <p>__ERROR__</p>
                <a href="__REDIRECT_URL__" class="btn">➜ Return to CRM</a>
                <div class="redirect-note">Redirecting in 3 seconds...</div>
              </div>
              <script>
                const target = '__REDIRECT_URL__';
                try {
                  if (window.opener) {
                    window.opener.postMessage({ type: 'META_GATEWAY_ERROR', error: '__ERROR__' }, '*');
                    setTimeout(() => { try { window.close(); } catch(e) {} }, 2500);
                  }
                } catch(e) {}
                setTimeout(() => { window.location.href = target; }, 3000);
              </script>
            </body>
            </html>
            """.replace("__ERROR__", errMsg).replace("__REDIRECT_URL__", targetUrl);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(errHtml);
        }

        User user = resolveUser(state);
        if (user == null) {
            String unauthHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Authentication Required | GyanVaniAi</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 24px; background: #F8FAFC; color: #0F172A; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                .card { background: #fff; border: 1px solid #E2E8F0; border-radius: 24px; padding: 36px 28px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.06); }
                .icon { font-size: 44px; margin-bottom: 12px; }
                h2 { color: #DC2626; font-size: 20px; font-weight: 800; margin-bottom: 8px; }
                p { color: #64748B; font-size: 13px; line-height: 1.5; margin-bottom: 24px; }
                .btn { display: inline-flex; align-items: center; justify-content: center; background: #1877F2; color: #fff; text-decoration: none; font-weight: 700; font-size: 14px; padding: 13px 24px; border-radius: 12px; width: 100%; transition: background 0.2s; }
                .btn:hover { background: #166fe5; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">🔒</div>
                <h2>Authentication Required</h2>
                <p>Please log in to your CRM account to link WhatsApp.</p>
                <a href="__REDIRECT_URL__" class="btn">➜ Return to Login</a>
              </div>
              <script>
                setTimeout(() => { window.location.href = '__REDIRECT_URL__'; }, 2500);
              </script>
            </body>
            </html>
            """.replace("__REDIRECT_URL__", targetUrl);
            return ResponseEntity.status(401).contentType(MediaType.TEXT_HTML).body(unauthHtml);
        }

        try {
            // Exchange code
            String longLivedToken = metaOnboardingClient.exchangeForLongLivedToken(code);
            Map<String, Object> debugData = metaOnboardingClient.debugToken(longLivedToken);
            String businessId = (String) debugData.get("businessId");
            LocalDateTime tokenExpiry = (LocalDateTime) debugData.get("tokenExpiry");
            String wabaId = (String) debugData.get("wabaId");
            if (!StringUtils.hasText(wabaId) && StringUtils.hasText(businessId)) {
                wabaId = metaOnboardingClient.fetchWabaId(businessId, longLivedToken);
            }

            Map<String, String> phoneDetails = new HashMap<>();
            if (StringUtils.hasText(wabaId)) {
                try {
                    phoneDetails = metaOnboardingClient.fetchPhoneNumberDetails(wabaId, longLivedToken);
                } catch (Exception e) {
                    log.warn("[MetaGateway Callback] Could not fetch phone details immediately: {}", e.getMessage());
                }
            }

            if (StringUtils.hasText(wabaId) && StringUtils.hasText(longLivedToken)) {
                try {
                    String subscribeUrl = String.format("https://graph.facebook.com/v19.0/%s/subscribed_apps?access_token=%s", wabaId, longLivedToken);
                    restTemplate.postForEntity(subscribeUrl, null, String.class);
                    log.info("[MetaGateway Callback] Successfully auto-subscribed WABA {} to webhooks", wabaId);
                } catch (Exception e) {
                    log.warn("[MetaGateway Callback] Auto-subscription warning: {}", e.getMessage());
                }
            }

            WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId()).orElse(new WhatsAppConfig());
            config.setUser(user);
            config.setConnectionType("EMBEDDED_SIGNUP_COEXISTENCE");
            config.setAccessToken(longLivedToken);
            config.setTokenExpiry(tokenExpiry);
            config.setBusinessId(businessId);
            config.setWabaId(wabaId);
            if (phoneDetails.containsKey("phoneNumberId")) config.setPhoneNumberId(phoneDetails.get("phoneNumberId"));
            if (phoneDetails.containsKey("displayPhoneNumber")) config.setDisplayPhoneNumber(phoneDetails.get("displayPhoneNumber"));
            if (phoneDetails.containsKey("verifiedName")) config.setVerifiedName(phoneDetails.get("verifiedName"));
            if (phoneDetails.containsKey("qualityRating")) config.setQualityRating(phoneDetails.get("qualityRating"));
            config.setVerificationStatus("VERIFIED");
            config.setAccountStatus("ACTIVE");
            if (!StringUtils.hasText(config.getVerifyToken())) {
                config.setVerifyToken("crm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            }
            whatsappConfigRepository.save(config);

            String successHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>WhatsApp Connected | GyanVaniAi</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 24px; background: #F8FAFC; color: #0F172A; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                .card { background: #fff; border: 1px solid #E2E8F0; border-radius: 24px; padding: 36px 28px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.06); }
                .icon { font-size: 48px; margin-bottom: 12px; }
                h2 { color: #059669; font-size: 22px; font-weight: 800; margin-bottom: 8px; }
                p { color: #475569; font-size: 14px; line-height: 1.5; margin-bottom: 24px; }
                .btn { display: inline-flex; align-items: center; justify-content: center; background: #1877F2; color: #fff; text-decoration: none; font-weight: 700; font-size: 14px; padding: 13px 24px; border-radius: 12px; width: 100%; transition: background 0.2s; }
                .btn:hover { background: #166fe5; }
                .redirect-note { margin-top: 14px; font-size: 12px; color: #94A3B8; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">✅</div>
                <h2>WhatsApp Connected!</h2>
                <p>Your WhatsApp Coexistence is active and verified. Automatically redirecting to your CRM dashboard...</p>
                <a href="__REDIRECT_URL__" id="returnBtn" class="btn">➜ Return to CRM Dashboard</a>
                <div class="redirect-note" id="timerText">Redirecting automatically in 2s...</div>
              </div>
              <script>
                const targetUrl = '__REDIRECT_URL__';
                try {
                  if (window.opener && !window.opener.closed) {
                    window.opener.postMessage({ type: 'META_WHATSAPP_CONNECTED', success: true }, '*');
                    setTimeout(() => { try { window.close(); } catch(e) {} }, 1200);
                  }
                } catch (e) {
                  console.warn('Opener postMessage error:', e);
                }
                setTimeout(() => {
                  window.location.href = targetUrl;
                }, 1600);
              </script>
            </body>
            </html>
            """.replace("__REDIRECT_URL__", targetUrl);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(successHtml);

        } catch (Exception e) {
            log.error("[MetaGateway Callback] Failed direct OAuth exchange", e);
            String failHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Connection Failed | GyanVaniAi</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding: 24px; background: #F8FAFC; color: #0F172A; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                .card { background: #fff; border: 1px solid #E2E8F0; border-radius: 24px; padding: 36px 28px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.06); }
                .icon { font-size: 44px; margin-bottom: 12px; }
                h2 { color: #DC2626; font-size: 20px; font-weight: 800; margin-bottom: 8px; }
                p { color: #64748B; font-size: 13px; line-height: 1.5; margin-bottom: 24px; }
                .btn { display: inline-flex; align-items: center; justify-content: center; background: #0F172A; color: #fff; text-decoration: none; font-weight: 700; font-size: 14px; padding: 13px 24px; border-radius: 12px; width: 100%; transition: background 0.2s; }
                .btn:hover { background: #1E293B; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">❌</div>
                <h2>Connection Failed</h2>
                <p>__ERROR__</p>
                <a href="__REDIRECT_URL__" class="btn">➜ Return to CRM</a>
              </div>
              <script>
                setTimeout(() => { window.location.href = '__REDIRECT_URL__'; }, 3500);
              </script>
            </body>
            </html>
            """.replace("__ERROR__", e.getMessage()).replace("__REDIRECT_URL__", targetUrl);
            return ResponseEntity.status(500).contentType(MediaType.TEXT_HTML).body(failHtml);
        }
    }
}
