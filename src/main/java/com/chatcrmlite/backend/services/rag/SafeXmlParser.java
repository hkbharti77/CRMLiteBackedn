package com.chatcrmlite.backend.services.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Component
public class SafeXmlParser {

    private static final Logger log = LoggerFactory.getLogger(SafeXmlParser.class);
    public static final int MAX_UNCOMPRESSED_BYTES = 10 * 1024 * 1024; // 10 MB limit
    public static final int MAX_COMPRESSION_RATIO = 100; // 100:1 bomb limit

    public record SitemapParseResult(
        List<String> pageUrls,
        List<String> childSitemapUrls
    ) {}

    public DocumentBuilder createSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);

        // Disallow DTD and external entities completely (XXE protection)
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            log.warn("[SafeXmlParser] Specific parser feature unsupported by XML parser: {}", e.getMessage());
        }

        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    public byte[] decompressIfGzipped(byte[] rawBytes) throws IOException {
        if (rawBytes == null || rawBytes.length < 2) {
            return rawBytes != null ? rawBytes : new byte[0];
        }

        // Check GZIP magic bytes: 0x1f, 0x8b
        if ((rawBytes[0] == (byte) 0x1f) && (rawBytes[1] == (byte) 0x8b)) {
            log.debug("[SafeXmlParser] Detected GZIP compressed XML. Decompressing with bomb defense...");
            ByteArrayInputStream byteIn = new ByteArrayInputStream(rawBytes);
            try (GZIPInputStream gzipIn = new GZIPInputStream(byteIn);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[8192];
                int totalDecompressed = 0;
                int n;

                while ((n = gzipIn.read(buffer)) != -1) {
                    totalDecompressed += n;

                    if (totalDecompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw new SecurityException("Decompression bomb detected: Uncompressed size exceeds " + 
                            (MAX_UNCOMPRESSED_BYTES / (1024 * 1024)) + " MB");
                    }

                    if (rawBytes.length > 0 && ((long) totalDecompressed / rawBytes.length) > MAX_COMPRESSION_RATIO) {
                        throw new SecurityException("Decompression bomb detected: Compression ratio exceeded 100:1");
                    }

                    out.write(buffer, 0, n);
                }

                return out.toByteArray();
            }
        }

        return rawBytes;
    }

    public SitemapParseResult parseSitemapXml(byte[] rawXmlBytes, String expectedHost) throws Exception {
        byte[] xmlBytes = decompressIfGzipped(rawXmlBytes);
        if (xmlBytes.length > MAX_UNCOMPRESSED_BYTES) {
            throw new SecurityException("XML content exceeds maximum size of 10 MB");
        }

        DocumentBuilder builder = createSecureDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));
        doc.getDocumentElement().normalize();

        List<String> pageUrls = new ArrayList<>();
        List<String> childSitemapUrls = new ArrayList<>();

        // 1. Check for standard <url><loc> entries
        NodeList urlNodes = doc.getElementsByTagName("url");
        for (int i = 0; i < urlNodes.getLength(); i++) {
            Element urlElement = (Element) urlNodes.item(i);
            NodeList locList = urlElement.getElementsByTagName("loc");
            if (locList.getLength() > 0) {
                String loc = locList.item(0).getTextContent();
                if (isValidSameOriginUrl(loc, expectedHost)) {
                    pageUrls.add(loc.trim());
                } else {
                    log.warn("[SafeXmlParser] Rejecting cross-host url in sitemap: {} (expected host: {})", loc, expectedHost);
                }
            }
        }

        // 2. Check for sitemap index <sitemap><loc> entries
        NodeList sitemapNodes = doc.getElementsByTagName("sitemap");
        for (int i = 0; i < sitemapNodes.getLength(); i++) {
            Element sitemapElement = (Element) sitemapNodes.item(i);
            NodeList locList = sitemapElement.getElementsByTagName("loc");
            if (locList.getLength() > 0) {
                String loc = locList.item(0).getTextContent();
                if (isValidSameOriginUrl(loc, expectedHost)) {
                    childSitemapUrls.add(loc.trim());
                } else {
                    log.warn("[SafeXmlParser] Rejecting cross-host child sitemap: {} (expected host: {})", loc, expectedHost);
                }
            }
        }

        return new SitemapParseResult(pageUrls, childSitemapUrls);
    }

    private boolean isValidSameOriginUrl(String rawUrl, String expectedHost) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost();
            if (host == null) return false;

            if (expectedHost == null || expectedHost.isBlank()) {
                return true; // No restriction specified
            }

            String lowerHost = host.toLowerCase();
            String lowerExpected = expectedHost.toLowerCase();

            // Match exact host or subdomain
            return lowerHost.equals(lowerExpected) || lowerHost.endsWith("." + lowerExpected);
        } catch (Exception e) {
            return false;
        }
    }
}
