package com.chatcrmlite.backend.services.email.verifier;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public interface InboundProviderVerifier {
    String getProviderName();
    boolean verify(HttpServletRequest request, Map<String, String> headers, String body);
}
