package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Lead;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

@Service
public class LeadExportService {

    public byte[] exportToExcel(List<Lead> leads, String businessName) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            
            Sheet sheet = workbook.createSheet("Leads Export");

            // Define Headers
            String[] headers = {
                "Lead ID", "Lead Number", "Contact Name", "Contact Phone (WaID)", "Contact Email",
                "Business Name", "Status", "Deal Value", "Currency", "Score",
                "Interest Category", "Owner", "Created At", "Last Activity"
            };

            // Header Style
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data Rows
            int rowIdx = 1;
            for (Lead lead : leads) {
                Row row = sheet.createRow(rowIdx++);
                
                row.createCell(0).setCellValue(lead.getId() != null ? lead.getId().toString() : "");
                row.createCell(1).setCellValue(lead.getLeadNumber() != null ? lead.getLeadNumber() : "");
                row.createCell(2).setCellValue(lead.getContact() != null ? lead.getContact().getName() : "");
                row.createCell(3).setCellValue(lead.getContact() != null ? lead.getContact().getWaId() : "");
                row.createCell(4).setCellValue(lead.getContact() != null ? lead.getContact().getEmail() : "");
                row.createCell(5).setCellValue(businessName != null ? businessName : "");
                row.createCell(6).setCellValue(lead.getStatus() != null ? lead.getStatus().name() : "");
                row.createCell(7).setCellValue(lead.getDealValue() != null ? lead.getDealValue().doubleValue() : 0.0);
                row.createCell(8).setCellValue(lead.getCurrency() != null ? lead.getCurrency() : "INR");
                row.createCell(9).setCellValue(lead.getScore() != null ? lead.getScore() : 0);
                row.createCell(10).setCellValue(lead.getInterestCategory() != null ? lead.getInterestCategory() : "");
                row.createCell(11).setCellValue(lead.getOwner() != null ? lead.getOwner().getDisplayName() : "");
                row.createCell(12).setCellValue(lead.getCreatedAt() != null ? lead.getCreatedAt().toString() : "");
                row.createCell(13).setCellValue(lead.getLastActivity() != null ? lead.getLastActivity().toString() : "");
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    public byte[] exportToCsv(List<Lead> leads, String businessName) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PrintWriter writer = new PrintWriter(out);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // Header Row
            printer.printRecord(
                "Lead ID", "Lead Number", "Contact Name", "Contact Phone (WaID)", "Contact Email",
                "Business Name", "Status", "Deal Value", "Currency", "Score",
                "Interest Category", "Owner", "Created At", "Last Activity"
            );

            for (Lead lead : leads) {
                printer.printRecord(
                    lead.getId() != null ? lead.getId().toString() : "",
                    lead.getLeadNumber() != null ? lead.getLeadNumber() : "",
                    lead.getContact() != null ? lead.getContact().getName() : "",
                    lead.getContact() != null ? lead.getContact().getWaId() : "",
                    lead.getContact() != null ? lead.getContact().getEmail() : "",
                    businessName != null ? businessName : "",
                    lead.getStatus() != null ? lead.getStatus().name() : "",
                    lead.getDealValue() != null ? lead.getDealValue().toString() : "0.0",
                    lead.getCurrency() != null ? lead.getCurrency() : "INR",
                    lead.getScore() != null ? lead.getScore().toString() : "0",
                    lead.getInterestCategory() != null ? lead.getInterestCategory() : "",
                    lead.getOwner() != null ? lead.getOwner().getDisplayName() : "",
                    lead.getCreatedAt() != null ? lead.getCreatedAt().toString() : "",
                    lead.getLastActivity() != null ? lead.getLastActivity().toString() : ""
                );
            }

            printer.flush();
            writer.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate CSV export", e);
        }
    }

    public void exportToCsvStream(java.io.Writer writer, Iterable<Lead> leads, String businessName, boolean includeHeader) throws java.io.IOException {
        CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
        if (includeHeader) {
            printer.printRecord(
                "Lead ID", "Lead Number", "Contact Name", "Contact Phone (WaID)", "Contact Email",
                "Business Name", "Status", "Deal Value", "Currency", "Score",
                "Interest Category", "Owner", "Created At", "Last Activity"
            );
        }

        for (Lead lead : leads) {
            printer.printRecord(
                lead.getId() != null ? lead.getId().toString() : "",
                lead.getLeadNumber() != null ? lead.getLeadNumber() : "",
                lead.getContact() != null ? lead.getContact().getName() : "",
                lead.getContact() != null ? lead.getContact().getWaId() : "",
                lead.getContact() != null ? lead.getContact().getEmail() : "",
                businessName != null ? businessName : "",
                lead.getStatus() != null ? lead.getStatus().name() : "",
                lead.getDealValue() != null ? lead.getDealValue().toString() : "0.0",
                lead.getCurrency() != null ? lead.getCurrency() : "INR",
                lead.getScore() != null ? lead.getScore() : 0,
                lead.getInterestCategory() != null ? lead.getInterestCategory() : "",
                lead.getOwner() != null ? lead.getOwner().getDisplayName() : "",
                lead.getCreatedAt() != null ? lead.getCreatedAt().toString() : "",
                lead.getLastActivity() != null ? lead.getLastActivity().toString() : ""
            );
        }
        printer.flush();
    }
}
