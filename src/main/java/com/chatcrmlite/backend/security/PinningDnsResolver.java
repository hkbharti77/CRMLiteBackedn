package com.chatcrmlite.backend.security;

import org.apache.hc.client5.http.DnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class PinningDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(PinningDnsResolver.class);
    private final SsrfProtectionValidator ssrfValidator;

    public PinningDnsResolver(SsrfProtectionValidator ssrfValidator) {
        this.ssrfValidator = ssrfValidator;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("No IP address could be resolved for target host: " + host);
        }

        // Validate EVERY returned address against SSRF defenses
        for (InetAddress addr : addresses) {
            ssrfValidator.validateAddress(addr, host);
        }

        // Return only the first validated address to pin the connection
        log.debug("[PinningDnsResolver] Successfully pinned host {} to IP {}", host, addresses[0].getHostAddress());
        return new InetAddress[] { addresses[0] };
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
