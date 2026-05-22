package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.repositories.LeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class LeadMetricsService {
    private static final Logger log = LoggerFactory.getLogger(LeadMetricsService.class);

    @Autowired
    private LeadRepository leadRepository;

    private final Map<String, AtomicLong> apiCallCounts   = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> apiTotalMs      = new ConcurrentHashMap<>();

    private final Map<UUID, List<LocalDateTime>> recentCreations = new ConcurrentHashMap<>();

    private static final int CREATION_ANOMALY_THRESHOLD = 10;

    public void recordApiCall(String endpoint, long durationMs) {
        apiCallCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0)).incrementAndGet();
        apiTotalMs.computeIfAbsent(endpoint, k -> new AtomicLong(0)).addAndGet(durationMs);

        if (durationMs > 200) {
            log.warn("[Lead-Metrics] Slow API response: {} took {}ms (SLA: 200ms)", endpoint, durationMs);
        }
    }

    public void recordLeadCreation(UUID ownerId, String contactWaId) {
        recentCreations.computeIfAbsent(ownerId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(LocalDateTime.now());

        List<LocalDateTime> times = recentCreations.get(ownerId);
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        long recentCount = times.stream().filter(t -> t.isAfter(oneMinuteAgo)).count();

        if (recentCount > CREATION_ANOMALY_THRESHOLD) {
            log.warn("[Lead-Alert] Unusual lead creation pattern detected! Owner {} created {} leads in the last 60s (contact: {})",
                    ownerId, recentCount, contactWaId);
        }

        times.removeIf(t -> t.isBefore(LocalDateTime.now().minusHours(1)));
    }

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

    @Scheduled(fixedDelay = 30 * 60 * 1000)
    @SchedulerLock(name = "LeadMetricsService_logPeriodicMetrics", lockAtMostFor = "25m", lockAtLeastFor = "10m")
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
