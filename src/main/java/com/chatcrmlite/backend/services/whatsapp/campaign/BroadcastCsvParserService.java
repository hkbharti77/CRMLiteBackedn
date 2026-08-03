package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.dto.BroadcastCsvUploadResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses CSV/XLSX files for WhatsApp broadcast audience targeting.
 *
 * <p>Unlike {@link com.chatcrmlite.backend.services.lead.BulkLeadParser} which only
 * maps 7 known columns, this service reads ALL columns dynamically (supporting 20–100+),
 * auto-detects the phone number column, and validates phone numbers.
 */
@Slf4j
@Service
public class BroadcastCsvParserService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final int MAX_COLUMNS = 100;
    private static final int SAMPLE_ROW_COUNT = 5;

    private static final Pattern E164_PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    /** Possible phone column names (case-insensitive matching). */
    private static final Set<String> PHONE_COLUMN_CANDIDATES = Set.of(
            "phone", "phonenumber", "phone_number", "mobile", "mobilenumber",
            "mobile_number", "contact", "contactnumber", "contact_number",
            "whatsapp", "wa_id", "waid", "cell", "cellphone", "telephone", "tel"
    );

    /**
     * Parses the uploaded file, detects all columns, auto-identifies the phone column,
     * validates phone numbers, and returns a comprehensive result DTO.
     *
     * @param file the uploaded CSV or XLSX file
     * @return result with column info, validation stats, and parsed data
     */
    public BroadcastCsvUploadResultDTO parseAndValidate(MultipartFile file) {
        // Guard: file size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File too large (max 10 MB)");
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();

        List<String> headers;
        List<Map<String, String>> allRows;

        if (filename.endsWith(".csv") || contentType.contains("text/csv")) {
            var parsed = parseCsv(file);
            headers = parsed.headers;
            allRows = parsed.rows;
        } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            var parsed = parseXlsx(file);
            headers = parsed.headers;
            allRows = parsed.rows;
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unsupported file type. Please upload a .csv, .xlsx, or .xls file.");
        }

        // Guard: column limit
        if (headers.size() > MAX_COLUMNS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "File has " + headers.size() + " columns, maximum allowed is " + MAX_COLUMNS);
        }

        // Guard: must have data rows
        if (allRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File has no data rows");
        }

        // Auto-detect phone column
        String phoneColumn = detectPhoneColumn(headers);
        if (phoneColumn == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No phone number column detected. Expected one of: phone, mobile, whatsapp, wa_id, contact_number, etc.");
        }

        // Validate phone numbers and deduplicate
        List<Map<String, String>> validRows = new ArrayList<>();
        List<BroadcastCsvUploadResultDTO.InvalidRowDTO> invalidRows = new ArrayList<>();
        Set<String> seenPhones = new HashSet<>();
        int duplicateCount = 0;

        for (int i = 0; i < allRows.size(); i++) {
            Map<String, String> row = allRows.get(i);
            String rawPhone = row.get(phoneColumn);

            if (rawPhone == null || rawPhone.isBlank()) {
                invalidRows.add(BroadcastCsvUploadResultDTO.InvalidRowDTO.builder()
                        .rowNumber(i + 2) // +2 because row 1 is header, data starts at row 2
                        .phone("")
                        .reason("Phone number is empty")
                        .build());
                continue;
            }

            String cleaned = rawPhone.replaceAll("[\\s\\-\\(\\)]", "");
            if (!E164_PHONE_PATTERN.matcher(cleaned).matches()) {
                invalidRows.add(BroadcastCsvUploadResultDTO.InvalidRowDTO.builder()
                        .rowNumber(i + 2)
                        .phone(rawPhone)
                        .reason("Invalid phone number format (expected E.164)")
                        .build());
                continue;
            }

            String normalized = cleaned.startsWith("+") ? cleaned : "+" + cleaned;

            if (seenPhones.contains(normalized)) {
                duplicateCount++;
                continue;
            }

            seenPhones.add(normalized);

            // Update the row with normalized phone
            Map<String, String> normalizedRow = new LinkedHashMap<>(row);
            normalizedRow.put(phoneColumn, normalized);
            validRows.add(normalizedRow);
        }

        // Build sample rows (first N from original data)
        List<Map<String, String>> sampleRows = allRows.stream()
                .limit(SAMPLE_ROW_COUNT)
                .collect(Collectors.toList());

        return BroadcastCsvUploadResultDTO.builder()
                .totalRows(allRows.size())
                .detectedColumns(headers)
                .phoneColumnName(phoneColumn)
                .validPhoneCount(validRows.size())
                .invalidPhoneCount(invalidRows.size())
                .duplicatePhoneCount(duplicateCount)
                .sampleRows(sampleRows)
                .invalidRows(invalidRows)
                .validRows(validRows)
                .build();
    }

    // ─── Phone Column Detection ──────────────────────────────────────────────

    private String detectPhoneColumn(List<String> headers) {
        for (String header : headers) {
            String normalized = header.toLowerCase().replaceAll("[\\s_\\-]", "");
            if (PHONE_COLUMN_CANDIDATES.contains(normalized)) {
                return header;
            }
        }
        // Fallback: partial match
        for (String header : headers) {
            String lower = header.toLowerCase();
            if (lower.contains("phone") || lower.contains("mobile") || lower.contains("whatsapp")) {
                return header;
            }
        }
        return null;
    }

    // ─── CSV Parsing ─────────────────────────────────────────────────────────

    private ParsedFile parseCsv(MultipartFile file) {
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(false) // preserve original case
                .setTrim(true)
                .build();

        try (CSVParser parser = format.parse(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "CSV file has no headers");
            }

            // Preserve header order
            List<String> headers = new ArrayList<>(headerMap.keySet());
            headers.sort(Comparator.comparingInt(headerMap::get));

            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : headers) {
                    String value = record.isMapped(header) ? record.get(header) : null;
                    row.put(header, (value == null || value.isBlank()) ? null : value);
                }
                rows.add(row);
            }

            return new ParsedFile(headers, rows);

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to read CSV file: " + e.getMessage());
        }
    }

    // ─── XLSX Parsing ────────────────────────────────────────────────────────

    private ParsedFile parseXlsx(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "XLSX file contains no sheets");
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "XLSX file has no header row");
            }

            // Read all headers
            List<String> headers = new ArrayList<>();
            Map<Integer, String> colIndexToHeader = new LinkedHashMap<>();
            for (int col = 0; col < headerRow.getLastCellNum(); col++) {
                Cell cell = headerRow.getCell(col);
                String headerName = getCellValue(cell);
                if (headerName != null && !headerName.isBlank()) {
                    headers.add(headerName.trim());
                    colIndexToHeader.put(col, headerName.trim());
                }
            }

            if (headers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "XLSX file has no valid column headers");
            }

            // Read data rows
            List<Map<String, String>> rows = new ArrayList<>();
            int lastRowNum = sheet.getLastRowNum();
            for (int i = 1; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowBlank(row, colIndexToHeader.keySet())) {
                    continue;
                }

                Map<String, String> rowMap = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> entry : colIndexToHeader.entrySet()) {
                    Cell cell = row.getCell(entry.getKey());
                    String value = getCellValue(cell);
                    rowMap.put(entry.getValue(), (value == null || value.isBlank()) ? null : value);
                }
                rows.add(rowMap);
            }

            return new ParsedFile(headers, rows);

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to read XLSX file: " + e.getMessage());
        }
    }

    // ─── Cell Helpers ────────────────────────────────────────────────────────

    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> {
                String v = cell.getStringCellValue();
                yield (v == null || v.isBlank()) ? null : v.trim();
            }
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK -> null;
            default -> null;
        };
    }

    private boolean isRowBlank(Row row, Set<Integer> relevantCols) {
        for (int col : relevantCols) {
            Cell cell = row.getCell(col);
            String value = getCellValue(cell);
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private record ParsedFile(List<String> headers, List<Map<String, String>> rows) {}
}
