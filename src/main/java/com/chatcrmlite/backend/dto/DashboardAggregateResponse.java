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
        private String date;
        private String time;
        private String contactName;
        private String status;
        private boolean isBooking;
    }
}
