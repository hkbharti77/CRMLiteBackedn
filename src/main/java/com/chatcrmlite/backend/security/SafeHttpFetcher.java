package com.chatcrmlite.backend.security;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Service
public class SafeHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeHttpFetcher.class);
    public static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB limit
    public static final int MAX_REDIRECT_HOPS = 5;

    private final SsrfProtectionValidator ssrfValidator;
    private final PinningDnsResolver pinningDnsResolver;

    public SafeHttpFetcher(SsrfProtectionValidator ssrfValidator, PinningDnsResolver pinningDnsResolver) {
        this.ssrfValidator = ssrfValidator;
        this.pinningDnsResolver = pinningDnsResolver;
    }

    public record HttpResponseResult(
        String finalUrl,
        int statusCode,
        String contentType,
        byte[] body,
        String bodyString
    ) {}

    public HttpResponseResult fetch(String targetUrl) throws IOException {
        String currentUrl = targetUrl;
        Set<String> visitedUrls = new HashSet<>();

        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            if (!visitedUrls.add(currentUrl)) {
                throw new SecurityException("SSRF / Redirect loop detected at hop " + hop + ": " + currentUrl);
            }

            // Step 1: Pre-flight SSRF validation on the target URL
            ssrfValidator.validateUrl(currentUrl);

            // Step 2: Build a fresh connection manager & client per request to ensure no socket pooling/reuse
            PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinningDnsResolver)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();

            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .setRedirectsEnabled(false) // Disable auto redirects
                .build();

            try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionReuseStrategy((request, response, context) -> false) // No connection reuse
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .setUserAgent("ChatCRM-Bot/1.0 (+https://chatcrmlite.com/bot; bot@chatcrmlite.com)")
                .build()) {

                ClassicHttpRequest request = ClassicRequestBuilder.get(currentUrl).build();

                String finalUrlCapture = currentUrl;
                HttpResponseResult result = client.execute(request, response -> {
                    int statusCode = response.getCode();
                    Header locationHeader = response.getFirstHeader("Location");

                    // Check for redirect (301, 302, 303, 307, 308)
                    if (statusCode >= 300 && statusCode < 400 && locationHeader != null) {
                        String redirectTarget = locationHeader.getValue();
                        URI baseUri = URI.create(finalUrlCapture);
                        URI resolvedUri = baseUri.resolve(redirectTarget);
                        return new HttpResponseResult(resolvedUri.toString(), statusCode, null, null, null);
                    }

                    if (statusCode < 200 || statusCode >= 300) {
                        throw new IOException("HTTP fetch failed with status code " + statusCode + " for " + finalUrlCapture);
                    }

                    HttpEntity entity = response.getEntity();
                    String contentType = entity != null && entity.getContentType() != null ? entity.getContentType() : "text/html";
                    byte[] bodyBytes;

                    if (entity != null) {
                        try (InputStream in = entity.getContent()) {
                            bodyBytes = readBoundedStream(in, MAX_RESPONSE_BYTES);
                        }
                    } else {
                        bodyBytes = new byte[0];
                    }

                    String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);
                    return new HttpResponseResult(finalUrlCapture, statusCode, contentType, bodyBytes, bodyString);
                });

                // If result has null body, it was a redirect
                if (result.body() == null && result.statusCode() >= 300 && result.statusCode() < 400) {
                    currentUrl = result.finalUrl();
                    log.info("[SafeHttpFetcher] Following validated redirect hop {} to {}", hop + 1, currentUrl);
                    continue;
                }

                return result;
            }
        }

        throw new IOException("Exceeded maximum redirect hops of " + MAX_REDIRECT_HOPS + " starting from " + targetUrl);
    }

    private byte[] readBoundedStream(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        int n;

        while ((n = in.read(buffer)) != -1) {
            totalRead += n;
            if (totalRead > maxBytes) {
                throw new SecurityException("Response size exceeded maximum allowable threshold of " + (maxBytes / (1024 * 1024)) + " MB");
            }
            out.write(buffer, 0, n);
        }

        return out.toByteArray();
    }
}
