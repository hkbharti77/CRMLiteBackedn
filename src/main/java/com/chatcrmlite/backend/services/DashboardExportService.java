package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.DashboardAggregateResponse;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardExportService {

    private final DashboardAggregateService dashboardAggregateService;
    private final AiOrchestrator aiOrchestrator;

    public byte[] exportReport(User user, String format) {
        DashboardAggregateResponse data = dashboardAggregateService.getDashboardData(user);
        String aiSummary = generateAiSummary(user, data);

        if ("pdf".equalsIgnoreCase(format)) {
            return generatePdfReport(data, aiSummary);
        } else {
            return generateCsvReport(data, aiSummary);
        }
    }

    private String generateAiSummary(User user, DashboardAggregateResponse data) {
        try {
            String prompt = String.format(
                "Analyze the following CRM dashboard metrics and provide a brief executive summary:\n" +
                "- Total Leads: %d\n" +
                "- Closed Leads: %d\n" +
                "- Open Tickets: %d\n" +
                "- Today's Meetings: %d\n" +
                "- Pipeline: %s\n" +
                "- Total Revenue: %s\n",
                data.getTotalLeads(),
                data.getClosedLeads(),
                data.getOpenTickets(),
                data.getTodayMeetings(),
                data.getPipeline().stream()
                    .map(p -> p.getStageName() + ": " + p.getCount())
                    .collect(Collectors.joining(", ")),
                data.getRevenueReport().getReceivedRevenue()
            );

            AiRequest request = AiRequest.builder()
                    .prompt(prompt)
                    .systemInstruction("You are a professional CRM data analyst. Provide a short, insightful executive summary based on the given metrics. Keep it under 150 words.")
                    .temperature(0.3)
                    .maxTokens(300)
                    .tenantId(user.getTenant() != null ? user.getTenant().getId() : user.getId())
                    .complexity(AiRequest.TaskComplexity.MEDIUM)
                    .build();

            AiResponse response = aiOrchestrator.execute(request);
            return response.getContent();
        } catch (Exception e) {
            log.warn("AI summary generation skipped: {}", e.getMessage());
            return "AI Summary generation is currently unavailable. Please configure your AI provider API keys.";
        }
    }

    private byte[] generateCsvReport(DashboardAggregateResponse data, String aiSummary) {
        try (StringWriter sw = new StringWriter();
             CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            
            // Header & AI Summary
            printer.printRecord("Dashboard Report");
            printer.printRecord("AI Executive Summary:");
            printer.printRecord(aiSummary);
            printer.printRecord();

            // KPIs
            printer.printRecord("Key Performance Indicators");
            printer.printRecord("Total Leads", "Closed Leads", "Open Tickets", "Today's Meetings", "Total Revenue");
            printer.printRecord(
                data.getTotalLeads(), 
                data.getClosedLeads(), 
                data.getOpenTickets(), 
                data.getTodayMeetings(),
                data.getRevenueReport().getReceivedRevenue()
            );
            printer.printRecord();

            // Pipeline
            printer.printRecord("Pipeline Distribution");
            printer.printRecord("Stage", "Count");
            for (var stage : data.getPipeline()) {
                printer.printRecord(stage.getStageName(), stage.getCount());
            }

            printer.flush();
            return sw.toString().getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Error generating CSV", e);
        }
    }

    private byte[] generatePdfReport(DashboardAggregateResponse data, String aiSummary) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            Font headerFont = new Font(Font.HELVETICA, 14, Font.BOLD);
            Font bodyFont = new Font(Font.HELVETICA, 12, Font.NORMAL);

            document.add(new Paragraph("Dashboard Report", titleFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("AI Executive Summary:", headerFont));
            document.add(new Paragraph(aiSummary, bodyFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Key Performance Indicators", headerFont));
            document.add(new Paragraph("Total Leads: " + data.getTotalLeads(), bodyFont));
            document.add(new Paragraph("Closed Leads: " + data.getClosedLeads(), bodyFont));
            document.add(new Paragraph("Open Tickets: " + data.getOpenTickets(), bodyFont));
            document.add(new Paragraph("Today's Meetings: " + data.getTodayMeetings(), bodyFont));
            document.add(new Paragraph("Total Revenue: " + data.getRevenueReport().getReceivedRevenue(), bodyFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Pipeline Distribution", headerFont));
            for (var stage : data.getPipeline()) {
                document.add(new Paragraph(stage.getStageName() + ": " + stage.getCount(), bodyFont));
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error generating PDF", e);
        }
    }
}
