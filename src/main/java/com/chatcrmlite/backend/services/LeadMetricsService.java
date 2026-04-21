package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Monitoring and observability service for the multiple-leads-per-contact feature.
 *
 * Tracks:
 * - 11.1 Leads-per-contact distribution
 * - 11.2 Lead creation patterns and anomalies
 * - 11.3 API response time tracking (via request counters)
 * - 11.4 Alerts for unusual lead creation patterns
 * - 11.5 Periodic metric summaries logged for dashboard consumption
 */
@Slf4j
@Service
public class LeadMetricsService {

    @Autowired
    private LeadRepository leadRepository;

    // ── 11.3 API response time tracking ───────────────────────────────────
    private final Map<String, AtomicLong> apiCallCounts   = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> apiTotalMs      = new ConcurrentHashMap<>();

    // ── 11.2 Lead creation tracking (per owner, per minute window) ────────
    private final Map<UUID, List<LocalDateTime>> recentCreations = new ConcurrentHashMap<>();

    // Threshold: more than 10 leads created in 1 minute = anomaly
    private static final int CREATION_ANOMALY_THRESHOLD = 10;

    // ── 11.3 Record API call timing ───────────────────────────────────────
    public void recordApiCall(String endpoint, long durationMs) {
        apiCallCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0)).incrementAndGet();
        apiTotalMs.computeIfAbsent(endpoint, k -> new AtomicLong(0)).addAndGet(durationMs);

        // Warn if response time exceeds 200ms SLA
        if (durationMs > 200) {
            log.warn("[Lead-Metrics] Slow API response: {} took {}ms (SLA: 200ms)", endpoint, durationMs);
        }
    }

    // ── 11.2 Track lead creation and detect anomalies ─────────────────────
    public void recordLeadCreation(UUID ownerId, String contactWaId) {
        recentCreations.computeIfAbsent(ownerId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(LocalDateTime.now());

        // Check for anomaly: too many leads created in last 60 seconds
        List<LocalDateTime> times = recentCreations.get(ownerId);
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        long recentCount = times.stream().filter(t -> t.isAfter(oneMinuteAgo)).count();

        if (recentCount > CREATION_ANOMALY_THRESHOLD) {
            // 11.4 Alert
            log.warn("[Lead-Alert] Unusual lead creation pattern detected! Owner {} created {} leads in the last 60s (contact: {})",
                    ownerId, recentCount, contactWaId);
        }

        // Prune old entries to prevent memory leak
        times.removeIf(t -> t.isBefore(LocalDateTime.now().minusHours(1)));
    }

    // ── 11.1 Leads-per-contact distribution ───────────────────────────────
    public Map<String, Object> getLeadsPerContactDistribution() {
        List<Lead> allLeads = leadRepository.findAll();

        Map<UUID, Long> countByContact = allLeads.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getContact().getId(),
                        Collectors.counting()
                ));

        long contactsWithOne    = countByContact.values().stream().filter(c -> c == 1).count();
        long contactsWithTwo    = countByContact.values().stream().filter(c -> c == 2).count();
        long contactsWithThree  = countByContact.values().stream().filter(c -> c == 3).count();
        long contactsWithMore   = countByContact.values().stream().filter(c -> c > 3).count();
        long maxLeadsPerContact = countByContact.values().stream().mapToLong(Long::longValue).max().orElse(0);
        double avgLeadsPerContact = countByContact.values().stream().mapToLong(Long::longValue).average().orElse(0);

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("totalLeads",           allLeads.size());
        distribution.put("totalContacts",         countByContact.size());
        distribution.put("contactsWith1Lead",     contactsWithOne);
        distribution.put("contactsWith2Leads",    contactsWithTwo);
        distribution.put("contactsWith3Leads",    contactsWithThree);
        distribution.put("contactsWithMoreLeads", contactsWithMore);
        distribution.put("maxLeadsPerContact",    maxLeadsPerContact);
        distribution.put("avgLeadsPerContact",    String.format("%.2f", avgLeadsPerContact));
        return distribution;
    }

    // ── 11.3 Get API performance summary ──────────────────────────────────
    public Map<String, Object> getApiPerformanceSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        apiCallCounts.forEach((endpoint, count) -> {
            long calls = count.get();
            long totalMs = apiTotalMs.getOrDefault(endpoint, new AtomicLong(0)).get();
            double avgMs = calls > 0 ? (double) totalMs / calls : 0;
            summary.put(endpoint, Map.of(
                    "calls",  calls,
                    "avgMs",  String.format("%.1f", avgMs),
                    "totalMs", totalMs
            ));
        });
        return summary;
    }

    // ── 11.5 Scheduled metric summary (every 30 minutes) ──────────────────
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void logPeriodicMetrics() {
        try {
            Map<String, Object> dist = getLeadsPerContactDistribution();
            log.info("[Lead-Metrics] Distribution snapshot: totalLeads={}, totalContacts={}, " +
                     "with1Lead={}, with2Leads={}, with3Leads={}, withMore={}, max={}, avg={}",
                    dist.get("totalLeads"), dist.get("totalContacts"),
                    dist.get("contactsWith1Lead"), dist.get("contactsWith2Leads"),
                    dist.get("contactsWith3Leads"), dist.get("contactsWithMoreLeads"),
                    dist.get("maxLeadsPerContact"), dist.get("avgLeadsPerContact"));

            Map<String, Object> perf = getApiPerformanceSummary();
            if (!perf.isEmpty()) {
                log.info("[Lead-Metrics] API performance snapshot: {}", perf);
            }
        } catch (Exception e) {
            log.error("[Lead-Metrics] Error logging periodic metrics", e);
        }
    }
}
