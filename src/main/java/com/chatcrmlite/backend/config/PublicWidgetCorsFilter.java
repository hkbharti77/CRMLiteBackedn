package com.chatcrmlite.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PublicWidgetCorsFilter
 * 
 * Ensures all public widget static assets (/widget/**, /chat-widget.js, /styles.css)
 * and public APIs (/api/v1/public/**) always return unrestricted CORS headers
 * (Access-Control-Allow-Origin: *) so the embeddable chat widget runs smoothly
 * on any external customer domain without CORS or 403 Forbidden errors.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicWidgetCorsFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String uri = request.getRequestURI();

        // Check if request is for public widget assets, static files, or public APIs
        if (isPublicWidgetResource(uri)) {
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "*");
            response.setHeader("Access-Control-Expose-Headers", "*");
            response.setHeader("Access-Control-Max-Age", "3600");

            // Handle browser CORS preflight directly
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private boolean isPublicWidgetResource(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/widget")
                || uri.startsWith("/api/v1/public")
                || uri.startsWith("/public")
                || uri.startsWith("/uploads")
                || uri.equals("/chat-widget.js")
                || uri.equals("/styles.css")
                || uri.equals("/test.html")
                || uri.endsWith(".js")
                || uri.endsWith(".css");
    }
}
