package com.chatcrmlite.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

@Component
public class SsrfProtectionValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfProtectionValidator.class);
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty or null");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URL syntax: " + rawUrl, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new SecurityException("SSRF Block: Only HTTP and HTTPS schemes are permitted: " + scheme);
        }

        int port = uri.getPort();
        if (port != -1 && !ALLOWED_PORTS.contains(port)) {
            throw new SecurityException("SSRF Block: Target port " + port + " is not permitted (only 80, 443)");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host cannot be empty");
        }

        // Validate host against loopback/internal domain strings
        String lowerHost = host.toLowerCase();
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost") || 
            lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
            throw new SecurityException("SSRF Block: Prohibited private host: " + host);
        }
    }

    public void validateAddress(InetAddress address, String originalHost) {
        if (address == null) {
            throw new IllegalArgumentException("Resolved address cannot be null");
        }

        byte[] bytes = address.getAddress();

        // 1. Check loopback
        if (address.isLoopbackAddress()) {
            throw new SecurityException("SSRF Block: Loopback address rejected: " + address.getHostAddress() + " (" + originalHost + ")");
        }

        // 2. Check any local address (0.0.0.0)
        if (address.isAnyLocalAddress()) {
            throw new SecurityException("SSRF Block: Any-local address rejected: " + address.getHostAddress());
        }

        // 3. Check link local address (169.254.x.x, fe80::/10)
        if (address.isLinkLocalAddress()) {
            throw new SecurityException("SSRF Block: Link-local / Cloud metadata address rejected: " + address.getHostAddress());
        }

        // 4. Check site local address (RFC 1918 private: 10.x, 172.16-31.x, 192.168.x)
        if (address.isSiteLocalAddress()) {
            throw new SecurityException("SSRF Block: RFC 1918 private subnet address rejected: " + address.getHostAddress());
        }

        // 5. IPv4 specific checks
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 0.0.0.0/8 (Broadcast/Current network)
            if (b0 == 0) {
                throw new SecurityException("SSRF Block: Zero-network address rejected: " + address.getHostAddress());
            }

            // 100.64.0.0/10 (Carrier-Grade NAT RFC 6598)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                throw new SecurityException("SSRF Block: Carrier-Grade NAT (RFC 6598) address rejected: " + address.getHostAddress());
            }

            // 127.0.0.0/8 (Loopback IPv4)
            if (b0 == 127) {
                throw new SecurityException("SSRF Block: Loopback address rejected: " + address.getHostAddress());
            }

            // 169.254.0.0/16 (Link Local / AWS/GCP/Azure Cloud Metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) {
                throw new SecurityException("SSRF Block: Cloud metadata address rejected: " + address.getHostAddress());
            }

            // 192.0.0.0/24 (IETF Protocol Assignments)
            if (b0 == 192 && b1 == 0 && (bytes[2] & 0xFF) == 0) {
                throw new SecurityException("SSRF Block: IETF reserved address rejected: " + address.getHostAddress());
            }

            // 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (Documentation/Test-Net)
            if ((b0 == 192 && b1 == 0 && (bytes[2] & 0xFF) == 2) ||
                (b0 == 198 && b1 == 51 && (bytes[2] & 0xFF) == 100) ||
                (b0 == 203 && b1 == 0 && (bytes[2] & 0xFF) == 113)) {
                throw new SecurityException("SSRF Block: Test-Net address rejected: " + address.getHostAddress());
            }

            // 224.0.0.0/4 (Multicast 224-239) & 240.0.0.0/4 (Reserved 240-255)
            if (b0 >= 224) {
                throw new SecurityException("SSRF Block: Multicast / Reserved address rejected: " + address.getHostAddress());
            }
        }

        // 6. IPv6 specific checks
        if (bytes.length == 16) {
            // IPv4-mapped IPv6 address (::ffff:x.x.x.x)
            boolean isIPv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isIPv4Mapped = false; break; }
            }
            if (isIPv4Mapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                // Extract mapped IPv4 and re-validate
                byte[] ipv4Bytes = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
                try {
                    InetAddress mappedIpv4 = InetAddress.getByAddress(ipv4Bytes);
                    validateAddress(mappedIpv4, originalHost);
                    return;
                } catch (Exception e) {
                    throw new SecurityException("SSRF Block: Malformed IPv4-mapped IPv6: " + address.getHostAddress(), e);
                }
            }

            // Unique Local Address (fc00::/7 -> fc00 to fdff)
            int firstByte = bytes[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                throw new SecurityException("SSRF Block: IPv6 Unique Local Address (fc00::/7) rejected: " + address.getHostAddress());
            }

            // IPv6 Multicast (ff00::/8)
            if (firstByte == 0xFF) {
                throw new SecurityException("SSRF Block: IPv6 Multicast address rejected: " + address.getHostAddress());
            }
        }
    }
}
