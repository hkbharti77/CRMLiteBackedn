package com.chatcrmlite.backend.services.platform;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.repositories.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Analytics service for the platform owner dashboard.
 *
 * Three-tier data strategy:
 * - DB: historical/business data (tenants, users, subscriptions, messages)
 * - Redis: real-time counters (AI requests, API requests)
 * - Actuator: system/infra metrics (exposed via /actuator separately)
 */
@Service
@Transactional(readOnly = true)
public class PlatformAnalyticsService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final MessageRepository messageRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final UserSessionRepository userSessionRepository;
    private final TicketRepository ticketRepository;
    private final LeadRepository leadRepository;
    private final StringRedisTemplate redisTemplate;

    public PlatformAnalyticsService(TenantRepository tenantRepository,
                                    UserRepository userRepository,
                                    TenantSubscriptionRepository subscriptionRepository,
                                    MessageRepository messageRepository,
                                    DocumentChunkRepository documentChunkRepository,
                                    UserSessionRepository userSessionRepository,
                                    TicketRepository ticketRepository,
                                    LeadRepository leadRepository,
                                    StringRedisTemplate redisTemplate) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.messageRepository = messageRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.userSessionRepository = userSessionRepository;
        this.ticketRepository = ticketRepository;
        this.leadRepository = leadRepository;
        this.redisTemplate = redisTemplate;
    }

    /** Platform overview — KPI counts from DB tables. */
    public Map<String, Object> getOverview() {
        long totalTenants = tenantRepository.count();
        long totalUsers = userRepository.count();
        long totalSubs = subscriptionRepository.count();
        long totalLeads = leadRepository != null ? leadRepository.count() : 0;

        List<TenantSubscription> allSubs = subscriptionRepository.findAll();
        long active = allSubs.stream().filter(s -> s.getStatus() == TenantSubscription.SubscriptionStatus.ACTIVE).count();
        long trial = allSubs.stream().filter(s -> s.getStatus() == TenantSubscription.SubscriptionStatus.FREE_TRIAL).count();
        long pastDue = allSubs.stream().filter(s -> s.getStatus() == TenantSubscription.SubscriptionStatus.PAST_DUE).count();
        long cancelled = allSubs.stream().filter(s -> s.getStatus() == TenantSubscription.SubscriptionStatus.CANCELLED).count();

        long totalTickets = ticketRepository != null ? ticketRepository.count() : 0;
        long openTickets = ticketRepository != null ? ticketRepository.findAll().stream()
            .filter(t -> !t.isDeleted() && (t.getStatus() == com.chatcrmlite.backend.models.Ticket.TicketStatus.OPEN || t.getStatus() == com.chatcrmlite.backend.models.Ticket.TicketStatus.IN_PROGRESS))
            .count() : 0;

        LocalDateTime in7Days = LocalDateTime.now().plusDays(7);
        long expiringSoon = allSubs.stream()
            .filter(s -> s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isBefore(in7Days)
                && s.getStatus() == TenantSubscription.SubscriptionStatus.ACTIVE)
            .count();

        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0);
        long newThisMonth = tenantRepository.findAll().stream()
            .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(startOfMonth))
            .count();

        // Calculate real MRR based on active tenants and their plan pricing
        long calculatedMrr = 0;
        List<Tenant> allTenants = tenantRepository.findAll();
        for (Tenant t : allTenants) {
            Tenant.LifecycleStatus status = t.getLifecycleStatus() != null ? t.getLifecycleStatus() : Tenant.LifecycleStatus.ACTIVE;
            if (status == Tenant.LifecycleStatus.ACTIVE) {
                String plan = t.getPlanType() != null ? t.getPlanType().name().toUpperCase() : "FREE";
                switch (plan) {
                    case "STARTER" -> calculatedMrr += 999;
                    case "GROWTH" -> calculatedMrr += 2499;
                    case "SCALE", "PRO" -> calculatedMrr += 4999;
                    case "ENTERPRISE" -> calculatedMrr += 9999;
                    default -> {}
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTenants", totalTenants);
        result.put("activeTenants", active > 0 ? active : totalTenants);
        result.put("totalUsers", totalUsers);
        result.put("totalLeads", totalLeads);
        result.put("mrr", calculatedMrr);
        result.put("totalSubscriptions", totalSubs);
        result.put("activeSubscriptions", active);
        result.put("trialSubscriptions", trial);
        result.put("pastDueSubscriptions", pastDue);
        result.put("cancelledSubscriptions", cancelled);
        result.put("expiringSoon", expiringSoon);
        result.put("newTenantsThisMonth", newThisMonth);
        result.put("totalTickets", totalTickets);
        result.put("openTickets", openTickets);
        return result;
    }

    /** Growth: new tenants per day for the last N days. */
    public List<Map<String, Object>> getGrowth(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Tenant> recent = tenantRepository.findAll().stream()
            .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(since))
            .toList();

        Map<String, Long> countByDay = new LinkedHashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        // init all days with 0
        for (int i = days - 1; i >= 0; i--) {
            countByDay.put(LocalDate.now().minusDays(i).format(fmt), 0L);
        }
        recent.forEach(t -> {
            String day = t.getCreatedAt().toLocalDate().format(fmt);
            countByDay.merge(day, 1L, Long::sum);
        });

        return countByDay.entrySet().stream()
            .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue(), "signups", e.getValue(), "churn", 0L))
            .toList();
    }

    /** Niche breakdown — businessType with count. */
    public List<Map<String, Object>> getNiches() {
        List<Tenant> all = tenantRepository.findAll();
        Map<String, Long> byType = new LinkedHashMap<>();
        all.forEach(t -> {
            String type = t.getBusinessType() != null && !t.getBusinessType().isBlank() ? t.getBusinessType() : "General";
            byType.merge(type, 1L, Long::sum);
        });
        return byType.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(e -> Map.<String, Object>of("niche", e.getKey(), "count", e.getValue()))
            .toList();
    }

    /** Subscription breakdown — plan distribution + status counts. */
    public Map<String, Object> getSubscriptionStats() {
        List<TenantSubscription> all = subscriptionRepository.findAll();
        List<Tenant> allTenants = tenantRepository.findAll();

        // Real Plan Distribution from tenants
        Map<String, Long> planBreakdown = new LinkedHashMap<>();
        planBreakdown.put("starter", 0L);
        planBreakdown.put("growth", 0L);
        planBreakdown.put("scale", 0L);
        planBreakdown.put("enterprise", 0L);

        for (Tenant t : allTenants) {
            String p = (t.getPlanType() != null ? t.getPlanType().name() : "STARTER").toLowerCase();
            if ("pro".equals(p) || "scale".equals(p)) planBreakdown.merge("scale", 1L, Long::sum);
            else if ("growth".equals(p)) planBreakdown.merge("growth", 1L, Long::sum);
            else if ("enterprise".equals(p)) planBreakdown.merge("enterprise", 1L, Long::sum);
            else planBreakdown.merge("starter", 1L, Long::sum);
        }

        // Status breakdown
        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        for (TenantSubscription.SubscriptionStatus s : TenantSubscription.SubscriptionStatus.values()) {
            long c = all.stream().filter(sub -> sub.getStatus() == s).count();
            statusBreakdown.put(s.name(), c);
        }

        // Expiring alerts
        LocalDateTime in7Days = LocalDateTime.now().plusDays(7);
        LocalDateTime in30Days = LocalDateTime.now().plusDays(30);
        long exp7 = all.stream().filter(s -> s.getCurrentPeriodEnd() != null
            && s.getCurrentPeriodEnd().isBefore(in7Days)
            && s.getStatus() == TenantSubscription.SubscriptionStatus.ACTIVE).count();
        long exp30 = all.stream().filter(s -> s.getCurrentPeriodEnd() != null
            && s.getCurrentPeriodEnd().isBefore(in30Days)
            && s.getStatus() == TenantSubscription.SubscriptionStatus.ACTIVE).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planBreakdown", planBreakdown);
        result.put("statusBreakdown", statusBreakdown);
        result.put("expiringIn7Days", exp7);
        result.put("expiringIn30Days", exp30);
        result.put("total", allTenants.size());
        return result;
    }

    /** Operational metrics — mixed DB + Redis. */
    public Map<String, Object> getOperationalMetrics() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // DB sources
        long totalDocs = documentChunkRepository.count();

        // Redis counters
        long aiRequests = getLongFromRedis("platform:ai_requests:" + today);
        long apiRequests = getLongFromRedis("platform:api_requests:" + today);
        long jobFailures = getLongFromRedis("platform:job_failures:" + today);

        // WhatsApp messages today — from messages table
        long waMessages;
        try {
            waMessages = messageRepository.findAll().stream()
                .filter(m -> m.getTimestamp() != null
                    && m.getTimestamp().toLocalDate().equals(LocalDate.now()))
                .count();
        } catch (Exception e) {
            waMessages = -1; // fallback if method unavailable
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiRequestsToday", aiRequests);
        result.put("apiRequestsToday", apiRequests);
        result.put("jobFailuresToday", jobFailures);
        result.put("whatsappMessagesToday", waMessages);
        result.put("totalDocuments", totalDocs);
        return result;
    }

    /** Churn — cancelled/inactive tenants in last 30 days. */
    public Map<String, Object> getChurn() {
        long totalTenants = tenantRepository.count();
        long churned = tenantRepository.findAll().stream()
            .filter(t -> t.getLifecycleStatus() == Tenant.LifecycleStatus.SUSPENDED 
                      || t.getLifecycleStatus() == Tenant.LifecycleStatus.ARCHIVED
                      || t.getLifecycleStatus() == Tenant.LifecycleStatus.DELETED)
            .count();

        double churnPct = totalTenants > 0 ? ((double) churned / totalTenants) * 100.0 : 0.0;
        String churnRateFormatted = String.format(Locale.US, "%.1f%%", churnPct);

        return Map.of(
            "churnRate", churnRateFormatted,
            "churnedLast30Days", churned,
            "totalChurned", churned,
            "totalTenants", totalTenants
        );
    }

    // ── Redis helpers ──────────────────────────────────────────────────────────

    private long getLongFromRedis(String key) {
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val != null ? Long.parseLong(val) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Called by PlatformMetricsInterceptor to increment daily AI counter. */
    public void incrementAiCounter() {
        String key = "platform:ai_requests:" + LocalDate.now();
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, java.time.Duration.ofDays(2));
    }

    /** Called by PlatformMetricsInterceptor to increment daily API counter. */
    public void incrementApiCounter() {
        String key = "platform:api_requests:" + LocalDate.now();
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, java.time.Duration.ofDays(2));
    }
}
