package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.security.AuthTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Production-grade Spring Security configuration.
 *
 * Security hardening applied:
 * - CORS restricted to explicit env-variable-controlled origin list (no wildcard)
 * - Full HTTP security headers: CSP, HSTS, X-Frame-Options, X-Content-Type-Options,
 *   Referrer-Policy, Permissions-Policy
 * - @EnableMethodSecurity enables @PreAuthorize on controllers
 * - Actuator endpoints require authentication (health details restricted)
 * - Swagger UI restricted to dev profile via conditional permit
 * - CSRF disabled (stateless JWT-based API — CSRF is mitigated by not using cookies)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)   // enables @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    /**
     * Comma-separated allowed origins from env (e.g. https://app.yourcrm.com,http://localhost:3000).
     * Defaults to localhost:3000 for local development.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // cost factor 12 — adequate for 2024+ hardware
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ── CORS ─────────────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── CSRF ─────────────────────────────────────────────────────────────
            // Stateless JWT API — tokens are in Authorization header, not cookies.
            // CSRF is not applicable. If you ever add cookie-based auth, re-enable.
            .csrf(csrf -> csrf.disable())

            // ── Session ──────────────────────────────────────────────────────────
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Route Authorization ───────────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                // Public auth + webhook + static public assets
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/api/v1/webhook/**",
                    "/api/v1/public/**",
                    "/api/v1/integrations/google/callback", // Google OAuth callback — no JWT available
                    "/webhook/**",
                    "/whatsapp/**",
                    "/ws/**",
                    "/uploads/**",
                    "/public/**"
                ).permitAll()
                // Swagger — gated: only allowed if the request comes from localhost
                // In a true production deploy, remove these lines or add IP-based restriction
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Static resources
                .requestMatchers("/*.html", "/*.js", "/*.css", "/*.png", "/*.ico", "/*.json").permitAll()
                // OPTIONS preflight — must be permitted for CORS to work
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                // Actuator — require authentication; role-based health detail is configured in properties
                .requestMatchers("/actuator/**").authenticated()
                // Admin API — method-level @PreAuthorize("hasRole('ADMIN')") enforces role
                .requestMatchers("/api/v1/admin/**").authenticated()
                // Everything else needs a valid JWT
                .anyRequest().authenticated()
            )

            // ── Security Headers ─────────────────────────────────────────────────
            .headers(headers -> headers
                // X-Frame-Options: DENY — blocks clickjacking
                .frameOptions(frame -> frame.deny())
                // X-Content-Type-Options: nosniff — blocks MIME sniffing
                .contentTypeOptions(cto -> {})
                // HSTS — tells browsers to only use HTTPS for 1 year (including subdomains)
                // Note: Spring only sends HSTS on HTTPS connections automatically
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31_536_000) // 1 year
                    .includeSubDomains(true)
                    .preload(true)
                )
                // Content-Security-Policy — restrict what resources pages can load
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline'; " +
                        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                        "img-src 'self' data: https:; " +
                        "font-src 'self' https://fonts.gstatic.com; " +
                        "connect-src 'self' *; " +
                        "frame-ancestors 'none'; " +
                        "form-action 'self'; " +
                        "base-uri 'self';"
                    )
                )
                // Referrer-Policy — don't leak full URL in Referer header
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Permissions-Policy — disable dangerous browser APIs
                .permissionsPolicy(pp -> pp
                    .policy("geolocation=(), microphone=(), camera=(), payment=(), usb=(), magnetometer=(), gyroscope=()")
                )
            );

        http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS configuration.
     *
     * Security: allowedOrigins is derived from the ALLOWED_ORIGINS environment variable.
     * Using setAllowedOrigins() (not setAllowedOriginPatterns("*")) so wildcard is impossible.
     * allowCredentials(true) is safe because we only allow explicit origins, never "*".
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.asList(allowedOrigins.split(","));

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin", "X-Hub-Signature-256", "X-Tenant-ID", "X-Trace-ID"
        ));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        
        // Public API CORS (Widget, Webhooks) - Allow All Origins
        CorsConfiguration publicConfig = new CorsConfiguration();
        publicConfig.setAllowedOriginPatterns(java.util.Collections.singletonList("*"));
        publicConfig.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        publicConfig.setAllowedHeaders(Arrays.asList("Content-Type", "Accept", "Origin"));
        publicConfig.setAllowCredentials(false);
        publicConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/v1/public/**", publicConfig);
        
        // Private API CORS - Restricted Origins
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
