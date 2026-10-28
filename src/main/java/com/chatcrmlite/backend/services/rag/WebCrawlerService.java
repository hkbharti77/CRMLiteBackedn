package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.KnowledgeDocument;
import com.chatcrmlite.backend.models.RagCrawlJob;
import com.chatcrmlite.backend.repositories.KnowledgeDocumentRepository;
import com.chatcrmlite.backend.repositories.RagCrawlJobRepository;
import com.chatcrmlite.backend.security.SafeHttpFetcher;
import com.chatcrmlite.backend.security.SafeHttpFetcher.HttpResponseResult;
import com.chatcrmlite.backend.services.RagIngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebCrawlerService {

    public static final int MAX_PAGES_PER_CRAWL = 50;

    private final SafeHttpFetcher safeHttpFetcher;
    private final SafeXmlParser safeXmlParser;
    private final SharedHostRateLimiter rateLimiter;
    private final TenantQuotaManager quotaManager;
    private final DurableCrawlQueue crawlQueue;
    private final RagCrawlJobRepository crawlJobRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final RagIngestionService ragIngestionService;
    private final ObjectMapper objectMapper;

    /**
     * Executes the crawl pipeline for a claimed RagCrawlJob.
     */
    public void executeCrawl(RagCrawlJob job) {
        UUID jobId = job.getId();
        UUID tenantId = job.getTenantId();
        int claimGen = job.getClaimGeneration();
        String sourceUrl = job.getSourceUrl();
        String crawlType = job.getCrawlType();

        log.info("Starting crawl execution for job {} (url: {}, type: {}, tenant: {})",
                jobId, sourceUrl, crawlType, tenantId);

        List<Map<String, Object>> partialFailures = new ArrayList<>();
        Set<String> visitedUrls = new HashSet<>();
        Queue<String> urlQueue = new ArrayDeque<>();
        urlQueue.add(sourceUrl);

        int pagesCrawled = 0;
        int pagesFailed = 0;
        int totalChunks = 0;
        UUID documentId = UUID.randomUUID();
        String documentTitle = "Web Ingestion: " + sourceUrl;

        try {
            // Determine dynamic page budget based on crawlType and tenant's plan quota
            com.chatcrmlite.backend.models.TenantRagQuota tenantQuota = quotaManager.getOrCreateQuota(tenantId);
            int availableDailyPages = Math.max(0, tenantQuota.getDailyPageLimit() - tenantQuota.getDailyPagesConsumed());
            int pageBudget;
            if ("SINGLE_PAGE".equalsIgnoreCase(crawlType)) {
                pageBudget = 1;
            } else {
                pageBudget = Math.min(MAX_PAGES_PER_CRAWL, Math.max(1, availableDailyPages));
            }
            int chunkBudget = pageBudget * 10;
            quotaManager.reserveQuota(tenantId, jobId, pageBudget, chunkBudget);

            // Step 1: Handle sitemap ingestion
            if ("SITEMAP_INDEX".equalsIgnoreCase(crawlType) || sourceUrl.endsWith(".xml") || sourceUrl.endsWith(".xml.gz")) {
                urlQueue.clear();
                List<String> sitemapUrls = parseSitemapUrls(sourceUrl, partialFailures);
                for (String u : sitemapUrls) {
                    if (urlQueue.size() < pageBudget) {
                        urlQueue.add(u);
                    }
                }
                crawlQueue.updateProgress(jobId, claimGen, urlQueue.size(), 0, 0, 0);
            }

            StringBuilder aggregateExtractedText = new StringBuilder();

            // Step 2: Crawl loop
            while (!urlQueue.isEmpty() && pagesCrawled < pageBudget) {
                String targetUrl = urlQueue.poll();
                if (targetUrl == null || !visitedUrls.add(targetUrl)) {
                    continue;
                }

                // Heartbeat & quota lease renewal every 5 pages
                if (pagesCrawled > 0 && pagesCrawled % 5 == 0) {
                    crawlQueue.renewHeartbeat(jobId, claimGen, Duration.ofMinutes(15));
                    quotaManager.renewReservation(jobId, Duration.ofMinutes(15));
                }

                try {
                    URI uri = URI.create(targetUrl);
                    String host = uri.getHost();

                    // Enforce host politeness pacing (>= 500ms)
                    rateLimiter.paceOutboundRequest(host);

                    HttpResponseResult result = safeHttpFetcher.fetch(targetUrl);
                    if (result.statusCode() != 200) {
                        pagesFailed++;
                        partialFailures.add(Map.of("url", targetUrl, "status", result.statusCode(), "error", "HTTP " + result.statusCode()));
                        continue;
                    }

                    // Parse HTML with JSoup
                    Document doc = Jsoup.parse(result.bodyString(), targetUrl);
                    doc.select("script, style, noscript, nav, footer, header, svg, iframe").remove();

                    String pageTitle = doc.title();
                    if (pageTitle != null && !pageTitle.isBlank() && pagesCrawled == 0) {
                        documentTitle = pageTitle.trim();
                    }

                    String cleanText = doc.body() != null ? doc.body().text() : "";
                    if (!cleanText.isBlank()) {
                        aggregateExtractedText.append("\n\n--- Source URL: ").append(targetUrl).append(" ---\n");
                        aggregateExtractedText.append("Title: ").append(pageTitle).append("\n\n");
                        aggregateExtractedText.append(cleanText);
                        pagesCrawled++;
                    }

                    // If recursive crawl, discover same-host child links
                    if ("RECURSIVE_CRAWL".equalsIgnoreCase(crawlType) && (urlQueue.size() + pagesCrawled) < MAX_PAGES_PER_CRAWL) {
                        Elements links = doc.select("a[href]");
                        for (Element link : links) {
                            String absHref = link.attr("abs:href");
                            if (isValidChildLink(absHref, host) && !visitedUrls.contains(absHref)) {
                                if ((urlQueue.size() + pagesCrawled) < MAX_PAGES_PER_CRAWL) {
                                    urlQueue.add(absHref);
                                }
                            }
                        }
                    }

                    crawlQueue.updateProgress(jobId, claimGen, urlQueue.size() + pagesCrawled, pagesCrawled, pagesFailed, 0);

                } catch (Exception e) {
                    pagesFailed++;
                    log.warn("Failed crawling page '{}' for job {}: {}", targetUrl, jobId, e.getMessage());
                    partialFailures.add(Map.of("url", targetUrl, "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                }
            }

            // Step 3: Ingest extracted text into Knowledge Document and Chunks
            if (pagesCrawled > 0 && aggregateExtractedText.length() > 0) {
                // Ingest into DocumentChunk repository
                var ingestionResult = ragIngestionService.ingestText(
                        aggregateExtractedText.toString(), tenantId, documentTitle, documentId).join();

                if ("COMPLETED".equals(ingestionResult.get("status"))) {
                    totalChunks = (Integer) ingestionResult.getOrDefault("chunksCount", 1);

                    // Update or create KnowledgeDocument record
                    KnowledgeDocument kd = knowledgeDocumentRepository.findById(documentId)
                            .orElseGet(() -> KnowledgeDocument.builder()
                                    .id(documentId)
                                    .tenantId(tenantId)
                                    .createdAt(Instant.now())
                                    .build());

                    kd.setName(documentTitle);
                    kd.setSourceType("WEB_CRAWL");
                    kd.setSourceUrl(sourceUrl);
                    kd.setActiveVersion(1);
                    kd.setStatus("ACTIVE");
                    kd.setTotalChunks(totalChunks);
                    kd.setUpdatedAt(Instant.now());
                    knowledgeDocumentRepository.save(kd);

                    // Commit quota reservation with actual usage
                    quotaManager.settleReservation(tenantId, jobId, pagesCrawled, totalChunks);

                    String finalStatus = (pagesFailed == 0) ? "COMPLETED" : "PARTIAL_SUCCESS";
                    String failuresJson = toJson(partialFailures);

                    crawlQueue.completeJob(jobId, claimGen, finalStatus, pagesCrawled, pagesFailed, totalChunks, failuresJson, null, documentId, documentTitle);
                    log.info("Crawl job {} completed with status {}: pagesCrawled={}, chunks={}, failures={}",
                            jobId, finalStatus, pagesCrawled, totalChunks, pagesFailed);
                } else {
                    quotaManager.releaseReservation(tenantId, jobId);
                    String failuresJson = toJson(partialFailures);
                    String errMsg = (String) ingestionResult.getOrDefault("error", "Chunk ingestion failed");
                    crawlQueue.completeJob(jobId, claimGen, "FAILED", pagesCrawled, pagesFailed, 0, failuresJson, errMsg, null, null);
                    log.warn("Crawl job {} ingestion failed: {}", jobId, errMsg);
                }
            } else {
                // Zero pages crawled successfully
                quotaManager.releaseReservation(tenantId, jobId);
                String failuresJson = toJson(partialFailures);
                crawlQueue.completeJob(jobId, claimGen, "FAILED", pagesCrawled, pagesFailed, 0, failuresJson, "No pages could be crawled successfully", null, null);
                log.warn("Crawl job {} failed: 0 pages successfully extracted", jobId);
            }

        } catch (Exception e) {
            log.error("Fatal error executing crawl job {}: {}", jobId, e.getMessage(), e);
            try {
                quotaManager.releaseReservation(tenantId, jobId);
            } catch (Exception ignored) {}

            String failuresJson = toJson(partialFailures);
            crawlQueue.completeJob(jobId, claimGen, "FAILED", pagesCrawled, pagesFailed, 0, failuresJson, e.getMessage(), null, null);
        }
    }

    private List<String> parseSitemapUrls(String sitemapUrl, List<Map<String, Object>> partialFailures) {
        List<String> discovered = new ArrayList<>();
        try {
            URI uri = URI.create(sitemapUrl);
            String host = uri.getHost();

            rateLimiter.paceOutboundRequest(host);
            HttpResponseResult res = safeHttpFetcher.fetch(sitemapUrl);
            if (res.statusCode() == 200) {
                SafeXmlParser.SitemapParseResult parseResult = safeXmlParser.parseSitemapXml(res.body(), host);
                discovered.addAll(parseResult.pageUrls());

                // Recursively follow child sitemaps (up to 3 child sitemaps)
                for (int i = 0; i < Math.min(3, parseResult.childSitemapUrls().size()); i++) {
                    String childUrl = parseResult.childSitemapUrls().get(i);
                    rateLimiter.paceOutboundRequest(host);
                    HttpResponseResult childRes = safeHttpFetcher.fetch(childUrl);
                    if (childRes.statusCode() == 200) {
                        SafeXmlParser.SitemapParseResult childParsed = safeXmlParser.parseSitemapXml(childRes.body(), host);
                        discovered.addAll(childParsed.pageUrls());
                    }
                }
            } else {
                partialFailures.add(Map.of("url", sitemapUrl, "error", "Sitemap HTTP " + res.statusCode()));
            }
        } catch (Exception e) {
            log.warn("Error parsing sitemap '{}': {}", sitemapUrl, e.getMessage());
            partialFailures.add(Map.of("url", sitemapUrl, "error", e.getMessage() != null ? e.getMessage() : "Sitemap parse error"));
        }
        return discovered;
    }

    private boolean isValidChildLink(String linkUrl, String expectedHost) {
        if (linkUrl == null || linkUrl.isBlank()) return false;
        try {
            URI uri = URI.create(linkUrl);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) return false;
            return host.equalsIgnoreCase(expectedHost) || host.toLowerCase().endsWith("." + expectedHost.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
