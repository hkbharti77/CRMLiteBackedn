package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardAggregateResponse {
    private long totalLeads;
    private long openTickets;
    private long closedLeads;
    private long todayMeetings;
    
    private List<PipelineStageCount> pipeline;
    private RevenueReportDTO revenueReport;
    private List<ActivityLogDTO> recentActivity;
    private List<DashboardMeetingDTO> todayMeetingsList;
    private List<DashboardMeetingDTO> upcomingMeetingsList;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageCount {
        private String stageName;
        private long count;
        private String color;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardMeetingDTO {
        private String id;
        private String title;
        /** ISO date string, e.g. "2026-07-08" */
        private String date;
        /** ISO time string, e.g. "14:30:00" */
        private String time;
        /** Full ISO datetime for sorting, e.g. "2026-07-08T14:30:00" */
        private String dateTime;
        private String contactName;
        private String status;
        private String meetingLink;
        private boolean isBooking;
    }
}
