package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.*;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.services.lead.LeadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardAggregateService {

    private final LeadService leadService;
    private final TicketService ticketService;
    private final ActivityLogService activityLogService;
    private final AppointmentService appointmentService;

    @Transactional(readOnly = true)
    public DashboardAggregateResponse getDashboardData(User user) {
        // 1. Leads
        long totalLeads = leadService.getTotalLeadCount(user);
        long wonCount = leadService.getLeadCountByStatus(Lead.LeadStatus.CLOSED_WON, user);
        long lostCount = leadService.getLeadCountByStatus(Lead.LeadStatus.CLOSED_LOST, user);
        long closedLeads = wonCount + lostCount;

        // Pipeline Distribution
        long newCount = leadService.getLeadCountByStatus(Lead.LeadStatus.NEW, user);
        long interestedCount = leadService.getLeadCountByStatus(Lead.LeadStatus.INTERESTED, user);
        long followUpCount = leadService.getLeadCountByStatus(Lead.LeadStatus.FOLLOW_UP, user);

        List<DashboardAggregateResponse.PipelineStageCount> pipeline = List.of(
                new DashboardAggregateResponse.PipelineStageCount("New", newCount, "#94A3B8"),
                new DashboardAggregateResponse.PipelineStageCount("Interested", interestedCount, "#0EA5E9"),
                new DashboardAggregateResponse.PipelineStageCount("Follow Up", followUpCount, "#F59E0B"),
                new DashboardAggregateResponse.PipelineStageCount("Won", wonCount, "#10B981")
        );

        // 2. Tickets
        long openTickets = ticketService.countOpenTickets(user);

        // 3. Revenue
        RevenueReportDTO revenueReport = leadService.getRevenueReport(user);

        // 4. Activity
        List<ActivityLogDTO> activityLogs = activityLogService.getOwnerFeed(user);
        List<ActivityLogDTO> recentActivity = activityLogs.size() > 6 
                ? activityLogs.subList(0, 6) 
                : activityLogs;

        // 5. Meetings (Appointments today)
        List<Appointment> todayAppts = appointmentService.getTodayAppointments(user);
        List<DashboardAggregateResponse.DashboardMeetingDTO> todayMeetingsList = todayAppts.stream()
            .map(appt -> DashboardAggregateResponse.DashboardMeetingDTO.builder()
                .id(appt.getId().toString())
                .title(appt.getTitle())
                .time(appt.getAppointmentDateTime().toString())
                .contactName(appt.getContact() != null ? appt.getContact().getName() : "Unknown")
                .isBooking(false)
                .build())
            .collect(Collectors.toList());

        return DashboardAggregateResponse.builder()
                .totalLeads(totalLeads)
                .openTickets(openTickets)
                .closedLeads(closedLeads)
                .todayMeetings(todayMeetingsList.size())
                .pipeline(pipeline)
                .revenueReport(revenueReport)
                .recentActivity(recentActivity)
                .todayMeetingsList(todayMeetingsList)
                .build();
    }
}
