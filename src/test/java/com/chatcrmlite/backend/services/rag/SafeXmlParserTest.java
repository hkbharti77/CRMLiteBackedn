package com.chatcrmlite.backend.services.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeXmlParserTest {

    private SafeXmlParser parser;

    @BeforeEach
    void setUp() {
        parser = new SafeXmlParser();
    }

    @Test
    @DisplayName("Should successfully parse a standard valid sitemap.xml")
    void testStandardSitemap() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url>
                    <loc>https://example.com/page1</loc>
                  </url>
                  <url>
                    <loc>https://example.com/page2</loc>
                  </url>
                </urlset>
                """;

        SafeXmlParser.SitemapParseResult result = parser.parseSitemapXml(
                xml.getBytes(StandardCharsets.UTF_8), "example.com");

        assertEquals(2, result.pageUrls().size());
        assertEquals("https://example.com/page1", result.pageUrls().get(0));
        assertEquals("https://example.com/page2", result.pageUrls().get(1));
        assertTrue(result.childSitemapUrls().isEmpty());
    }

    @Test
    @DisplayName("Should parse a sitemapindex with child sitemaps")
    void testSitemapIndex() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap>
                    <loc>https://example.com/sitemap-blog.xml</loc>
                  </sitemap>
                  <sitemap>
                    <loc>https://example.com/sitemap-products.xml</loc>
                  </sitemap>
                </sitemapindex>
                """;

        SafeXmlParser.SitemapParseResult result = parser.parseSitemapXml(
                xml.getBytes(StandardCharsets.UTF_8), "example.com");

        assertTrue(result.pageUrls().isEmpty());
        assertEquals(2, result.childSitemapUrls().size());
        assertEquals("https://example.com/sitemap-blog.xml", result.childSitemapUrls().get(0));
        assertEquals("https://example.com/sitemap-products.xml", result.childSitemapUrls().get(1));
    }

    @Test
    @DisplayName("Should reject cross-host URLs from sitemap")
    void testCrossHostRejection() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url>
                    <loc>https://example.com/safe</loc>
                  </url>
                  <url>
                    <loc>https://evil.com/phishing</loc>
                  </url>
                </urlset>
                """;

        SafeXmlParser.SitemapParseResult result = parser.parseSitemapXml(
                xml.getBytes(StandardCharsets.UTF_8), "example.com");

        assertEquals(1, result.pageUrls().size());
        assertEquals("https://example.com/safe", result.pageUrls().get(0));
    }

    @Test
    @DisplayName("Should block XXE entity expansion attacks")
    void testXxeRejection() {
        String xxeXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                  <!ELEMENT foo ANY >
                  <!ENTITY xxe SYSTEM "file:///etc/passwd" >
                ]>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url>
                    <loc>&xxe;</loc>
                  </url>
                </urlset>
                """;

        assertThrows(Exception.class, () -> 
                parser.parseSitemapXml(xxeXml.getBytes(StandardCharsets.UTF_8), "example.com"));
    }

    @Test
    @DisplayName("Should parse valid GZIP compressed sitemap.xml.gz")
    void testGzipCompressedSitemap() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url>
                    <loc>https://example.com/compressed-page</loc>
                  </url>
                </urlset>
                """;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzippedBytes = baos.toByteArray();

        SafeXmlParser.SitemapParseResult result = parser.parseSitemapXml(gzippedBytes, "example.com");
        assertEquals(1, result.pageUrls().size());
        assertEquals("https://example.com/compressed-page", result.pageUrls().get(0));
    }
}
