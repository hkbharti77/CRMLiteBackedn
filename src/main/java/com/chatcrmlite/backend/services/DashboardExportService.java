package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.DashboardAggregateResponse;
import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
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
    private final NicheThemeService themeService;

    public byte[] exportReport(User user, String format) {
        DashboardAggregateResponse data = dashboardAggregateService.getDashboardData(user);
        String aiSummary = generateAiSummary(user, data);

        if ("pdf".equalsIgnoreCase(format)) {
            return generatePdfReport(data, aiSummary, user);
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

    private byte[] generatePdfReport(DashboardAggregateResponse data, String aiSummary, User user) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            // Load theme configuration
            ThemeConfigDTO theme = themeService.getThemeForUser(user);
            java.awt.Color primaryColor = java.awt.Color.decode(theme.getPrimaryColor() != null ? theme.getPrimaryColor() : "#0F172A");
            java.awt.Color secondaryColor = java.awt.Color.decode(theme.getSecondaryColor() != null ? theme.getSecondaryColor() : "#3B82F6");
            java.awt.Color lightTint = getLightTint(primaryColor);
            
            // Fonts
            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD, primaryColor);
            Font subtitleFont = new Font(Font.HELVETICA, 9, Font.ITALIC, java.awt.Color.GRAY);
            Font sectionHeaderFont = new Font(Font.HELVETICA, 13, Font.BOLD, primaryColor);
            Font bodyFont = new Font(Font.HELVETICA, 10, Font.NORMAL, java.awt.Color.DARK_GRAY);
            Font boldBodyFont = new Font(Font.HELVETICA, 10, Font.BOLD, java.awt.Color.BLACK);
            Font tableHeaderFont = new Font(Font.HELVETICA, 10, Font.BOLD, java.awt.Color.WHITE);
            Font cardValFont = new Font(Font.HELVETICA, 18, Font.BOLD, secondaryColor);
            Font cardLabelFont = new Font(Font.HELVETICA, 9, Font.NORMAL, java.awt.Color.GRAY);

            // 1. TOP HEADER BANNER
            PdfPTable headerTable = new PdfPTable(1);
            headerTable.setWidthPercentage(100);
            
            PdfPCell bannerCell = new PdfPCell(new Phrase(user.getBusinessName() != null ? user.getBusinessName().toUpperCase() : "CRM REPORT", tableHeaderFont));
            bannerCell.setBackgroundColor(primaryColor);
            bannerCell.setPadding(8f);
            bannerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            bannerCell.setBorder(PdfPCell.NO_BORDER);
            headerTable.addCell(bannerCell);
            document.add(headerTable);
            
            document.add(new Paragraph(" "));

            // 2. REPORT TITLE
            Paragraph reportTitle = new Paragraph("CRM Performance Dashboard", titleFont);
            reportTitle.setAlignment(Element.ALIGN_LEFT);
            document.add(reportTitle);
            
            String nicheLabel = user.getBusinessSubType() != null ? user.getBusinessSubType().replace("-", " ") : "Business Analytics";
            String dateLabel = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy - hh:mm a"));
            Paragraph subtitle = new Paragraph("Niche: " + nicheLabel + "  |  Generated: " + dateLabel, subtitleFont);
            subtitle.setAlignment(Element.ALIGN_LEFT);
            document.add(subtitle);

            document.add(new Paragraph(" "));
            
            // 3. AI EXECUTIVE SUMMARY CALLOUT BOX
            document.add(new Paragraph("AI Executive Summary", sectionHeaderFont));
            document.add(new Paragraph(" "));
            
            PdfPTable summaryTable = new PdfPTable(1);
            summaryTable.setWidthPercentage(100);
            
            // Remove markdown formatting from AI response for cleaner PDF layout
            String cleanSummary = aiSummary.replaceAll("\\*\\*.*?\\*\\*\\s*", "");
            
            PdfPCell summaryCell = new PdfPCell(new Phrase(cleanSummary, bodyFont));
            summaryCell.setBackgroundColor(lightTint);
            summaryCell.setPadding(12f);
            summaryCell.setBorder(PdfPCell.LEFT);
            summaryCell.setBorderColorLeft(primaryColor);
            summaryCell.setBorderWidthLeft(4f);
            summaryCell.setBorderColorRight(java.awt.Color.LIGHT_GRAY);
            summaryCell.setBorderColorTop(java.awt.Color.LIGHT_GRAY);
            summaryCell.setBorderColorBottom(java.awt.Color.LIGHT_GRAY);
            summaryTable.addCell(summaryCell);
            document.add(summaryTable);

            document.add(new Paragraph(" "));
            
            // 4. KEY METRICS CARDS GRID (Using Table)
            document.add(new Paragraph("Key Performance Indicators", sectionHeaderFont));
            document.add(new Paragraph(" "));
            
            PdfPTable kpiGrid = new PdfPTable(4);
            kpiGrid.setWidthPercentage(100);
            kpiGrid.setSpacingBefore(5f);
            kpiGrid.setSpacingAfter(15f);
            kpiGrid.setWidths(new float[]{1f, 1f, 1f, 1f});
            
            long totalLeads = data.getTotalLeads();
            long closedLeads = data.getClosedLeads();
            double convRate = totalLeads > 0 ? (double)(closedLeads * 100) / totalLeads : 0.0;
            
            // Cells
            kpiGrid.addCell(createKpiCell("Total Leads", String.valueOf(totalLeads), cardLabelFont, cardValFont));
            kpiGrid.addCell(createKpiCell("Conversions", String.valueOf(closedLeads), cardLabelFont, cardValFont));
            kpiGrid.addCell(createKpiCell("Conv. Rate", String.format("%.1f%%", convRate), cardLabelFont, cardValFont));
            kpiGrid.addCell(createKpiCell("Meetings Today", String.valueOf(data.getTodayMeetings()), cardLabelFont, cardValFont));
            
            document.add(kpiGrid);

            // Revenue Bar / Block
            PdfPTable revTable = new PdfPTable(1);
            revTable.setWidthPercentage(100);
            String revStr = data.getRevenueReport() != null && data.getRevenueReport().getReceivedRevenue() != null 
                    ? data.getRevenueReport().getReceivedRevenue().toString() : "0";
            PdfPCell revCell = new PdfPCell(new Phrase("Total Received Revenue: $" + revStr + "  |  Open Tickets: " + data.getOpenTickets(), boldBodyFont));
            revCell.setBackgroundColor(getLightTint(secondaryColor));
            revCell.setPadding(8f);
            revCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            revCell.setBorder(PdfPCell.BOX);
            revCell.setBorderColor(secondaryColor);
            revTable.addCell(revCell);
            document.add(revTable);

            document.add(new Paragraph(" "));
            
            // 5. PIPELINE DISTRIBUTION TABLE
            document.add(new Paragraph("Sales Pipeline Distribution", sectionHeaderFont));
            document.add(new Paragraph(" "));
            
            PdfPTable pipelineTable = new PdfPTable(3);
            pipelineTable.setWidthPercentage(100);
            pipelineTable.setWidths(new float[]{2f, 1f, 1f});
            
            // Table Headers
            PdfPCell h1 = new PdfPCell(new Phrase("Pipeline Stage", tableHeaderFont));
            h1.setBackgroundColor(primaryColor);
            h1.setPadding(6f);
            h1.setBorder(PdfPCell.BOX);
            h1.setBorderColor(primaryColor);
            pipelineTable.addCell(h1);
            
            PdfPCell h2 = new PdfPCell(new Phrase("Leads Count", tableHeaderFont));
            h2.setBackgroundColor(primaryColor);
            h2.setPadding(6f);
            h2.setHorizontalAlignment(Element.ALIGN_CENTER);
            h2.setBorder(PdfPCell.BOX);
            h2.setBorderColor(primaryColor);
            pipelineTable.addCell(h2);
            
            PdfPCell h3 = new PdfPCell(new Phrase("Percentage", tableHeaderFont));
            h3.setBackgroundColor(primaryColor);
            h3.setPadding(6f);
            h3.setHorizontalAlignment(Element.ALIGN_CENTER);
            h3.setBorder(PdfPCell.BOX);
            h3.setBorderColor(primaryColor);
            pipelineTable.addCell(h3);
            
            // Add rows with alternating background colors
            boolean isOdd = true;
            for (var stage : data.getPipeline()) {
                java.awt.Color rowBg = isOdd ? java.awt.Color.WHITE : new java.awt.Color(245, 247, 250);
                
                PdfPCell c1 = new PdfPCell(new Phrase(stage.getStageName(), bodyFont));
                c1.setBackgroundColor(rowBg);
                c1.setPadding(6f);
                c1.setBorder(PdfPCell.BOX);
                c1.setBorderColor(java.awt.Color.LIGHT_GRAY);
                pipelineTable.addCell(c1);
                
                PdfPCell c2 = new PdfPCell(new Phrase(String.valueOf(stage.getCount()), bodyFont));
                c2.setBackgroundColor(rowBg);
                c2.setPadding(6f);
                c2.setHorizontalAlignment(Element.ALIGN_CENTER);
                c2.setBorder(PdfPCell.BOX);
                c2.setBorderColor(java.awt.Color.LIGHT_GRAY);
                pipelineTable.addCell(c2);
                
                double pct = totalLeads > 0 ? (double)(stage.getCount() * 100) / totalLeads : 0.0;
                PdfPCell c3 = new PdfPCell(new Phrase(String.format("%.1f%%", pct), bodyFont));
                c3.setBackgroundColor(rowBg);
                c3.setPadding(6f);
                c3.setHorizontalAlignment(Element.ALIGN_CENTER);
                c3.setBorder(PdfPCell.BOX);
                c3.setBorderColor(java.awt.Color.LIGHT_GRAY);
                pipelineTable.addCell(c3);
                
                isOdd = !isOdd;
            }
            
            document.add(pipelineTable);
            
            // Footer branding
            document.add(new Paragraph(" "));
            Paragraph footer = new Paragraph("Generated by ChatCRM Lite — Personalized Niche CRM Platform", subtitleFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error generating PDF", e);
        }
    }

    private PdfPCell createKpiCell(String label, String value, Font labelFont, Font valFont) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(8f);
        cell.setBackgroundColor(new java.awt.Color(250, 250, 250));
        cell.setBorder(PdfPCell.BOX);
        cell.setBorderColor(java.awt.Color.LIGHT_GRAY);
        
        Paragraph pLabel = new Paragraph(label, labelFont);
        pLabel.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pLabel);
        
        Paragraph pVal = new Paragraph(value, valFont);
        pVal.setAlignment(Element.ALIGN_CENTER);
        pVal.setSpacingBefore(2f);
        cell.addElement(pVal);
        
        return cell;
    }

    private java.awt.Color getLightTint(java.awt.Color color) {
        int r = Math.min(255, (int)(color.getRed() + (255 - color.getRed()) * 0.95));
        int g = Math.min(255, (int)(color.getGreen() + (255 - color.getGreen()) * 0.95));
        int b = Math.min(255, (int)(color.getBlue() + (255 - color.getBlue()) * 0.95));
        return new java.awt.Color(r, g, b);
    }
}
