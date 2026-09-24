package com.chatcrmlite.backend.services.journey;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.journey.BulkUploadBatch;
import com.chatcrmlite.backend.models.journey.BulkUploadRowError;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.journey.BulkUploadBatchRepository;
import com.chatcrmlite.backend.repositories.journey.BulkUploadRowErrorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BulkContactIngestionService {

    private final BulkUploadBatchRepository batchRepository;
    private final BulkUploadRowErrorRepository rowErrorRepository;
    private final ContactRepository contactRepository;
    private final TenantRepository tenantRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    /**
     * Initializes a Bulk Upload batch and starts asynchronous file processing.
     */
    @Transactional
    public BulkUploadBatch initializeAndProcessBatch(
            String businessId,
            String fileName,
            String fileType,
            byte[] fileBytes,
            String uploadedBy,
            Map<String, String> columnMapping,
            UUID autoTriggerJourneyId) throws Exception {

        String checksum = calculateSha256(fileBytes);

        Optional<BulkUploadBatch> duplicateCheck = batchRepository.findByBusinessIdAndFileChecksum(businessId, checksum);
        if (duplicateCheck.isPresent()) {
            throw new IllegalArgumentException("Duplicate file upload detected. A batch with identical checksum already exists (Batch ID: " + duplicateCheck.get().getId() + ")");
        }

        String mappingJson = objectMapper.writeValueAsString(columnMapping != null ? columnMapping : Collections.emptyMap());

        BulkUploadBatch batch = BulkUploadBatch.builder()
                .businessId(businessId)
                .fileName(fileName)
                .fileType(fileType.toUpperCase())
                .fileChecksum(checksum)
                .fileSize((long) fileBytes.length)
                .uploadedBy(uploadedBy)
                .columnMapping(mappingJson)
                .status("QUEUED")
                .autoTriggerJourneyId(autoTriggerJourneyId)
                .build();

        BulkUploadBatch savedBatch = batchRepository.save(batch);
        log.info("[BulkIngestion] Initialized batch {} for file {} (biz={})", savedBatch.getId(), fileName, businessId);

        // Async processing
        processBatchAsync(savedBatch.getId(), fileBytes, columnMapping);

        return savedBatch;
    }

    @Async
    public void processBatchAsync(UUID batchId, byte[] fileBytes, Map<String, String> columnMapping) {
        log.info("[BulkIngestionAsync] Starting ingestion for batch {}", batchId);
        Optional<BulkUploadBatch> batchOpt = batchRepository.findById(batchId);
        if (batchOpt.isEmpty()) {
            log.error("[BulkIngestionAsync] Batch {} not found", batchId);
            return;
        }

        BulkUploadBatch batch = batchOpt.get();
        batch.setStatus("PROCESSING");
        batchRepository.save(batch);

        int total = 0, valid = 0, invalid = 0, imported = 0, updated = 0, duplicates = 0, failed = 0;

        Tenant tenant = tenantRepository.findById(UUID.fromString(batch.getBusinessId())).orElse(null);

        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            List<Map<String, String>> rows = parseFileRows(is, batch.getFileType(), columnMapping);
            total = rows.size();
            batch.setTotalRows(total);
            batchRepository.save(batch);

            for (int i = 0; i < rows.size(); i++) {
                int rowNumber = i + 1;
                Map<String, String> row = rows.get(i);

                String name = row.getOrDefault("name", row.getOrDefault("first_name", "")).trim();
                String rawPhone = row.getOrDefault("phone", row.getOrDefault("mobile", "")).trim();
                String email = row.getOrDefault("email", "").trim();

                String phone = normalizePhone(rawPhone);

                if (phone.isEmpty() && email.isEmpty()) {
                    invalid++;
                    failed++;
                    logRowError(batch, rowNumber, row, "INVALID_CONTACT", "Row must contain at least a valid phone or email");
                    continue;
                }

                try {
                    Optional<Contact> existingContact = Optional.empty();
                    if (!phone.isEmpty()) {
                        existingContact = contactRepository.findByWaIdAndTenant_Id(phone, UUID.fromString(batch.getBusinessId()));
                    }

                    Contact contact;
                    if (existingContact.isPresent()) {
                        contact = existingContact.get();
                        if (!name.isEmpty()) contact.setName(name);
                        if (!email.isEmpty()) contact.setEmail(email);
                        updated++;
                    } else {
                        contact = Contact.builder()
                                .waId(!phone.isEmpty() ? phone : "EML_" + UUID.randomUUID().toString().substring(0, 8))
                                .name(!name.isEmpty() ? name : "Contact " + rowNumber)
                                .email(email)
                                .source("BULK_UPLOAD:" + batch.getFileName())
                                .build();
                        if (tenant != null) {
                            contact.setTenant(tenant);
                        }
                        imported++;
                    }

                    Contact savedContact = contactRepository.save(contact);
                    valid++;

                    // Trigger journey automation outbox event if configured
                    if (batch.getAutoTriggerJourneyId() != null) {
                        UUID businessEventId = UUID.nameUUIDFromBytes((batchId.toString() + ":" + savedContact.getId()).getBytes(StandardCharsets.UTF_8));
                        String payloadJson = objectMapper.writeValueAsString(Map.of(
                                "contact_id", savedContact.getId().toString(),
                                "name", savedContact.getName() != null ? savedContact.getName() : "",
                                "phone", savedContact.getWaId() != null ? savedContact.getWaId() : "",
                                "email", savedContact.getEmail() != null ? savedContact.getEmail() : "",
                                "journey_id", batch.getAutoTriggerJourneyId().toString(),
                                "source", "BULK_UPLOAD"
                        ));

                        outboxPublisherService.publishEvent(
                                businessEventId,
                                batch.getBusinessId(),
                                "BULK_UPLOAD",
                                savedContact.getId().toString(),
                                "EVENT_BULK_UPLOAD",
                                payloadJson
                        );
                    }

                } catch (Exception e) {
                    failed++;
                    invalid++;
                    logRowError(batch, rowNumber, row, "SAVE_FAILED", e.getMessage());
                }
            }

            batch.setValidRows(valid);
            batch.setInvalidRows(invalid);
            batch.setImportedContacts(imported);
            batch.setUpdatedContacts(updated);
            batch.setDuplicateRows(duplicates);
            batch.setFailedRows(failed);
            batch.setStatus(failed == 0 ? "COMPLETED" : (valid > 0 ? "PARTIAL_SUCCESS" : "FAILED"));
            batch.setCompletedAt(ZonedDateTime.now());
            batchRepository.save(batch);

            log.info("[BulkIngestionAsync] Completed batch {}: total={}, imported={}, updated={}, failed={}", batchId, total, imported, updated, failed);

        } catch (Exception e) {
            log.error("[BulkIngestionAsync] Batch {} failed completely: {}", batchId, e.getMessage(), e);
            batch.setStatus("FAILED");
            batch.setCompletedAt(ZonedDateTime.now());
            batchRepository.save(batch);
        }
    }

    public String generateFailedRowsCsv(String businessId, UUID batchId) {
        List<BulkUploadRowError> errors = rowErrorRepository.findByBusinessIdAndBatchId(businessId, batchId);
        StringBuilder sb = new StringBuilder();
        sb.append("Row Number,Error Code,Error Message,Raw Data\n");
        for (BulkUploadRowError err : errors) {
            sb.append(err.getRowNumber()).append(",")
                    .append("\"").append(err.getErrorCode()).append("\",")
                    .append("\"").append(err.getErrorMessage().replace("\"", "'")).append("\",")
                    .append("\"").append(err.getRawData() != null ? err.getRawData().replace("\"", "'") : "").append("\"\n");
        }
        return sb.toString();
    }

    private List<Map<String, String>> parseFileRows(InputStream is, String fileType, Map<String, String> columnMapping) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        if ("CSV".equalsIgnoreCase(fileType) || "TXT".equalsIgnoreCase(fileType)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                 CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreSurroundingSpaces().withTrim())) {

                Map<String, Integer> headerMap = parser.getHeaderMap();
                for (CSVRecord record : parser) {
                    Map<String, String> row = new HashMap<>();
                    for (Map.Entry<String, Integer> entry : headerMap.entrySet()) {
                        String rawHeader = entry.getKey();
                        String normalizedHeader = mapHeader(rawHeader, columnMapping);
                        row.put(normalizedHeader, record.get(entry.getValue()));
                    }
                    rows.add(row);
                }
            }
        } else if ("EXCEL".equalsIgnoreCase(fileType) || "XLSX".equalsIgnoreCase(fileType)) {
            Workbook workbook = WorkbookFactory.create(is);
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                Map<Integer, String> colMap = new HashMap<>();
                for (Cell cell : headerRow) {
                    String rawHeader = cell.getStringCellValue();
                    colMap.put(cell.getColumnIndex(), mapHeader(rawHeader, columnMapping));
                }
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row dataRow = sheet.getRow(r);
                    if (dataRow == null) continue;
                    Map<String, String> row = new HashMap<>();
                    for (Map.Entry<Integer, String> entry : colMap.entrySet()) {
                        Cell cell = dataRow.getCell(entry.getKey());
                        String val = cell != null ? getCellValueAsString(cell) : "";
                        row.put(entry.getValue(), val);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private String mapHeader(String rawHeader, Map<String, String> mapping) {
        if (rawHeader == null) return "";
        String cleanHeader = rawHeader.trim();
        if (mapping != null && mapping.containsKey(cleanHeader)) {
            return mapping.get(cleanHeader).toLowerCase();
        }
        String lower = cleanHeader.toLowerCase();
        if (lower.contains("name") || lower.contains("first")) return "name";
        if (lower.contains("phone") || lower.contains("mobile") || lower.contains("wa")) return "phone";
        if (lower.contains("email") || lower.contains("mail")) return "email";
        return lower;
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) return digits;
        if (digits.length() == 10) return "+91" + digits; // Default India prefix
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        return digits;
    }

    private String getCellValueAsString(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private void logRowError(BulkUploadBatch batch, int rowNumber, Map<String, String> rawData, String errorCode, String errorMessage) {
        try {
            BulkUploadRowError error = BulkUploadRowError.builder()
                    .batchId(batch.getId())
                    .businessId(batch.getBusinessId())
                    .rowNumber(rowNumber)
                    .rawData(objectMapper.writeValueAsString(rawData))
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
            rowErrorRepository.save(error);
        } catch (Exception e) {
            log.error("[BulkIngestion] Failed to log row error for batch {}: {}", batch.getId(), e.getMessage());
        }
    }

    private String calculateSha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
