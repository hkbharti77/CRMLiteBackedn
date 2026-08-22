package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LeadExportServiceTest {

    private final LeadExportService exportService = new LeadExportService();

    @Test
    @DisplayName("Streaming CSV export writes headers on first batch and data rows cleanly")
    void testExportToCsvStream() throws Exception {
        List<Lead> batch = new ArrayList<>();

        Contact contact = new Contact();
        contact.setName("Alice Walker");
        contact.setWaId("919876543210");
        contact.setEmail("alice@example.com");

        Lead lead1 = new Lead();
        lead1.setId(UUID.randomUUID());
        lead1.setLeadNumber("LEAD-001");
        lead1.setContact(contact);
        lead1.setStatus(Lead.LeadStatus.NEW);
        lead1.setDealValue(new BigDecimal("50000.00"));
        lead1.setCurrency("INR");
        lead1.setScore(85);
        lead1.setInterestCategory("Enterprise SaaS");
        lead1.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        lead1.setLastActivity(LocalDateTime.of(2026, 8, 1, 12, 0));
        batch.add(lead1);

        StringWriter writer = new StringWriter();
        exportService.exportToCsvStream(writer, batch, "Acme Corp", true);

        String csvOutput = writer.toString();
        assertTrue(csvOutput.contains("Lead ID,Lead Number,Contact Name"));
        assertTrue(csvOutput.contains("LEAD-001"));
        assertTrue(csvOutput.contains("Alice Walker"));
        assertTrue(csvOutput.contains("919876543210"));
        assertTrue(csvOutput.contains("Acme Corp"));
        assertTrue(csvOutput.contains("50000.00"));

        // Second batch without header
        StringWriter secondWriter = new StringWriter();
        exportService.exportToCsvStream(secondWriter, batch, "Acme Corp", false);
        String secondOutput = secondWriter.toString();
        assertFalse(secondOutput.contains("Lead ID,Lead Number,Contact Name"));
        assertTrue(secondOutput.contains("LEAD-001"));
    }

    @Test
    @DisplayName("Excel export generates valid binary content")
    void testExportToExcel() {
        List<Lead> leads = new ArrayList<>();
        Contact contact = new Contact();
        contact.setName("Bob Smith");
        Lead lead = new Lead();
        lead.setId(UUID.randomUUID());
        lead.setContact(contact);
        lead.setStatus(Lead.LeadStatus.CONTACTED);
        leads.add(lead);

        byte[] excelBytes = exportService.exportToExcel(leads, "Acme Corp");
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 100);
    }
}
