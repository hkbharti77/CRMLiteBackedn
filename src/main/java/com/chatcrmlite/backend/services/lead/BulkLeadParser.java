package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses bulk lead upload files (CSV and XLSX) into a list of {@link BulkLeadRowDTO}.
 *
 * <p>Guard rails (applied before / during parsing):
 * <ul>
 *   <li>File size &gt; 5 MB → HTTP 413 PAYLOAD_TOO_LARGE</li>
 *   <li>Missing {@code name} column header → HTTP 422 UNPROCESSABLE_ENTITY</li>
 *   <li>Zero data rows after the header → HTTP 400 BAD_REQUEST</li>
 * </ul>
 *
 * <p>Requirement 8 — Backend File Parsing
 */
@Service
public class BulkLeadParser {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5 MB

    /** Known lead field column names (used for case-insensitive header mapping). */
    private static final List<String> KNOWN_HEADERS = List.of(
            "name", "email", "phone", "source", "status", "notes", "tags"
    );

    /**
     * Parses the given multipart file into a list of {@link BulkLeadRowDTO}.
     *
     * @param file the uploaded CSV or XLSX file
     * @return non-empty list of parsed rows with 1-based row numbers
     * @throws ResponseStatusException if the file is too large, missing the {@code name} header,
     *                                  or contains no data rows
     */
    public List<BulkLeadRowDTO> parse(MultipartFile file) {
        // Guard: file size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large (max 5 MB)");
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();

        if (filename.endsWith(".csv") || contentType.contains("text/csv")) {
            return parseCsv(file);
        } else if (filename.endsWith(".xlsx")) {
            return parseXlsx(file);
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unsupported file type. Please upload a .csv or .xlsx file.");
        }
    }

    // -------------------------------------------------------------------------
    // CSV parsing
    // -------------------------------------------------------------------------

    private List<BulkLeadRowDTO> parseCsv(MultipartFile file) {
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        List<BulkLeadRowDTO> rows = new ArrayList<>();

        try (CSVParser parser = format.parse(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            // Guard: name column must be present
            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || !headerMap.containsKey("name")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Missing required column: name");
            }

            int rowNumber = 1;
            for (CSVRecord record : parser) {
                BulkLeadRowDTO dto = BulkLeadRowDTO.builder()
                        .rowNumber(rowNumber++)
                        .name(getHeader(record, "name"))
                        .email(getHeader(record, "email"))
                        .phone(getHeader(record, "phone"))
                        .source(getHeader(record, "source"))
                        .status(getHeader(record, "status"))
                        .notes(getHeader(record, "notes"))
                        .tags(getHeader(record, "tags"))
                        .build();
                rows.add(dto);
            }

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to read CSV file: " + e.getMessage());
        }

        // Guard: must have at least one data row
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File has no data rows");
        }

        return rows;
    }

    /**
     * Returns the trimmed value for a header if it exists in the record, or {@code null} if absent.
     * Unknown headers are silently ignored by only querying known fields.
     */
    private String getHeader(CSVRecord record, String header) {
        if (record.isMapped(header)) {
            String value = record.get(header);
            return (value == null || value.isBlank()) ? null : value;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // XLSX parsing
    // -------------------------------------------------------------------------

    private List<BulkLeadRowDTO> parseXlsx(MultipartFile file) {
        List<BulkLeadRowDTO> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            if (sheet == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "XLSX file contains no sheets.");
            }

            // Row 0 = header row → build case-insensitive header→columnIndex map
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Missing required column: name");
            }

            Map<String, Integer> headerIndex = buildHeaderIndex(headerRow);

            // Guard: name column must be present
            if (!headerIndex.containsKey("name")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Missing required column: name");
            }

            // Iterate from row index 1 onward
            int lastRowNum = sheet.getLastRowNum();
            for (int i = 1; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowBlank(row)) {
                    continue;
                }

                BulkLeadRowDTO dto = BulkLeadRowDTO.builder()
                        .rowNumber(i) // 1-based: row index 1 = row number 1
                        .name(getCellByHeader(row, headerIndex, "name"))
                        .email(getCellByHeader(row, headerIndex, "email"))
                        .phone(getCellByHeader(row, headerIndex, "phone"))
                        .source(getCellByHeader(row, headerIndex, "source"))
                        .status(getCellByHeader(row, headerIndex, "status"))
                        .notes(getCellByHeader(row, headerIndex, "notes"))
                        .tags(getCellByHeader(row, headerIndex, "tags"))
                        .build();
                rows.add(dto);
            }

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to read XLSX file: " + e.getMessage());
        }

        // Guard: must have at least one data row
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File has no data rows");
        }

        return rows;
    }

    /**
     * Builds a lowercase header name → column index map from the header row.
     * Only known fields are stored; unknown columns are silently ignored.
     */
    private Map<String, Integer> buildHeaderIndex(Row headerRow) {
        Map<String, Integer> index = new HashMap<>();
        for (int col = 0; col <= headerRow.getLastCellNum(); col++) {
            Cell cell = headerRow.getCell(col);
            if (cell == null) continue;
            String headerName = getCellValue(cell);
            if (headerName == null || headerName.isBlank()) continue;
            String normalized = headerName.trim().toLowerCase();
            if (KNOWN_HEADERS.contains(normalized)) {
                index.put(normalized, col);
            }
        }
        return index;
    }

    /**
     * Returns {@code true} if every cell in the row is blank or the row is null.
     */
    private boolean isRowBlank(Row row) {
        for (int col = row.getFirstCellNum(); col <= row.getLastCellNum(); col++) {
            Cell cell = row.getCell(col);
            String value = getCellValue(cell);
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Retrieves and trims the cell value for the given header from the row.
     * Returns {@code null} if the header is not in the index or the value is blank.
     */
    private String getCellByHeader(Row row, Map<String, Integer> headerIndex, String header) {
        Integer colIndex = headerIndex.get(header);
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        String value = getCellValue(cell);
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Converts an Apache POI {@link Cell} to a String regardless of its type.
     *
     * @param cell the cell to read (may be {@code null})
     * @return the string representation, or {@code null} for blank/null cells
     */
    String getCellValue(Cell cell) {
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
                // Avoid ".0" suffix for whole numbers
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
}
