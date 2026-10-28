package com.chatcrmlite.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class SsrfProtectionValidatorTest {

    private SsrfProtectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SsrfProtectionValidator();
    }

    @Test
    @DisplayName("Should allow valid public HTTP/HTTPS URLs on standard ports")
    void testValidPublicUrls() {
        assertDoesNotThrow(() -> validator.validateUrl("https://example.com/path"));
        assertDoesNotThrow(() -> validator.validateUrl("http://example.com:80/about"));
        assertDoesNotThrow(() -> validator.validateUrl("https://docs.github.com:443/en/get-started"));
    }

    @Test
    @DisplayName("Should reject disallowed schemes")
    void testDisallowedSchemes() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("file:///etc/passwd"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("ftp://example.com/file.txt"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("gopher://example.com"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("javascript:alert(1)"));
    }

    @Test
    @DisplayName("Should reject disallowed non-standard ports")
    void testDisallowedPorts() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://example.com:22"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://example.com:5432"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://example.com:6379"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://example.com:8080"));
    }

    @Test
    @DisplayName("Should reject loopback and private IPv4 addresses")
    void testPrivateIpv4Addresses() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThrows(SecurityException.class, () -> validator.validateAddress(loopback, "127.0.0.1"));

        InetAddress privA = InetAddress.getByName("10.0.1.5");
        assertThrows(SecurityException.class, () -> validator.validateAddress(privA, "10.0.1.5"));

        InetAddress privB = InetAddress.getByName("172.16.0.100");
        assertThrows(SecurityException.class, () -> validator.validateAddress(privB, "172.16.0.100"));

        InetAddress privC = InetAddress.getByName("192.168.1.1");
        assertThrows(SecurityException.class, () -> validator.validateAddress(privC, "192.168.1.1"));
    }

    @Test
    @DisplayName("Should reject AWS / GCP cloud metadata address 169.254.169.254")
    void testCloudMetadataAddress() throws Exception {
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        assertThrows(SecurityException.class, () -> validator.validateAddress(metadata, "169.254.169.254"));
    }

    @Test
    @DisplayName("Should reject IPv4-mapped IPv6 representations of private IPs")
    void testIpv4MappedIpv6() throws Exception {
        byte[] mappedLoopback = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1
        };
        InetAddress addr = InetAddress.getByAddress(mappedLoopback);
        assertThrows(SecurityException.class, () -> validator.validateAddress(addr, "mapped-loopback"));

        byte[] mappedMetadata = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 169, (byte) 254, (byte) 169, (byte) 254
        };
        InetAddress metaAddr = InetAddress.getByAddress(mappedMetadata);
        assertThrows(SecurityException.class, () -> validator.validateAddress(metaAddr, "mapped-metadata"));
    }

    @Test
    @DisplayName("Should allow valid public IP addresses")
    void testPublicIpAddress() throws Exception {
        InetAddress googleDns = InetAddress.getByName("8.8.8.8");
        assertDoesNotThrow(() -> validator.validateAddress(googleDns, "dns.google"));

        InetAddress cloudflareDns = InetAddress.getByName("1.1.1.1");
        assertDoesNotThrow(() -> validator.validateAddress(cloudflareDns, "one.one.one.one"));
    }
}
