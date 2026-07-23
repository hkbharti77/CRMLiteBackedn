package com.chatcrmlite.backend.services.team;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TicketRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentAnalyticsService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentPerformanceDTO {
        private UUID agentId;
        private String displayName;
        private String email;
        private User.Role role;
        private User.AvailabilityStatus availabilityStatus;
        private double avgResponseTimeMinutes;
        private double ticketResolutionRatePercent;
        private long totalDealsClosed;
        private BigDecimal totalRevenueWon;
        private long activeAssignedWorkload;
    }

    @Transactional(readOnly = true)
    public List<AgentPerformanceDTO> getTeamPerformanceForTenant(Tenant tenant) {
        if (tenant == null) return List.of();
        List<User> teamMembers = userRepository.findAllByTenant(tenant);
        List<AgentPerformanceDTO> result = new ArrayList<>();

        for (User agent : teamMembers) {
            // 1. Deals Closed & Revenue Won
            List<Lead> agentLeads = leadRepository.findAllByOwner(agent);
            long closedDeals = agentLeads.stream()
                    .filter(l -> l.getStatus() == Lead.LeadStatus.CLOSED_WON)
                    .count();

            BigDecimal totalRevenue = agentLeads.stream()
                    .filter(l -> l.getStatus() == Lead.LeadStatus.CLOSED_WON && l.getDealValue() != null)
                    .map(Lead::getDealValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 2. Tickets Resolution Rate
            List<Ticket> agentTickets = ticketRepository.findAllByAssignedTo(agent);
            long totalTickets = agentTickets.size();
            long resolvedTickets = agentTickets.stream()
                    .filter(t -> t.getStatus() == Ticket.TicketStatus.RESOLVED || t.getStatus() == Ticket.TicketStatus.CLOSED)
                    .count();
            double resolutionRate = totalTickets > 0 ? (double) resolvedTickets / totalTickets * 100.0 : 100.0;

            // 3. Active Workload
            long openLeadsCount = agentLeads.stream()
                    .filter(l -> l.getStatus() != Lead.LeadStatus.CLOSED_WON && l.getStatus() != Lead.LeadStatus.CLOSED_LOST)
                    .count();
            long openTicketsCount = agentTickets.stream()
                    .filter(t -> t.getStatus() != Ticket.TicketStatus.RESOLVED && t.getStatus() != Ticket.TicketStatus.CLOSED)
                    .count();
            long totalWorkload = openLeadsCount + openTicketsCount;

            // 4. Mock Avg Response Time (Default 2.5 minutes if no data)
            double avgResponseMinutes = 2.5;

            result.add(AgentPerformanceDTO.builder()
                    .agentId(agent.getId())
                    .displayName(agent.getDisplayName() != null ? agent.getDisplayName() : agent.getFirstName())
                    .email(agent.getEmail())
                    .role(agent.getRole())
                    .availabilityStatus(agent.getAvailabilityStatus())
                    .avgResponseTimeMinutes(Math.round(avgResponseMinutes * 10.0) / 10.0)
                    .ticketResolutionRatePercent(Math.round(resolutionRate * 10.0) / 10.0)
                    .totalDealsClosed(closedDeals)
                    .totalRevenueWon(totalRevenue)
                    .activeAssignedWorkload(totalWorkload)
                    .build());
        }

        return result;
    }
}
