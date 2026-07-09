package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BulkLeadParser}.
 *
 * <p>No Spring context is loaded — the parser is instantiated directly with {@code new BulkLeadParser()}.
 *
 * <p>Validates: Requirements 8 (Backend File Parsing)
 */
class BulkLeadParserTest {

    private BulkLeadParser parser;

    @BeforeEach
    void setUp() {
        parser = new BulkLeadParser();
    }

    // ── 1. Valid CSV — all fields parse correctly ─────────────────────────

    @Test
    @DisplayName("Valid CSV with all fields parses correctly")
    void validCsvAllFields() {
        String csv = "name,email,phone,source,status,notes,tags\n" +
                     "Alice,alice@example.com,9876543210,WhatsApp,NEW,First contact,vip\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        List<BulkLeadRowDTO> rows = parser.parse(file);

        assertThat(rows).hasSize(1);
        BulkLeadRowDTO row = rows.get(0);
        assertThat(row.getRowNumber()).isEqualTo(1);
        assertThat(row.getName()).isEqualTo("Alice");
        assertThat(row.getEmail()).isEqualTo("alice@example.com");
        assertThat(row.getPhone()).isEqualTo("9876543210");
        assertThat(row.getSource()).isEqualTo("WhatsApp");
        assertThat(row.getStatus()).isEqualTo("NEW");
        assertThat(row.getNotes()).isEqualTo("First contact");
        assertThat(row.getTags()).isEqualTo("vip");
    }

    // ── 2. Valid XLSX — all fields parse correctly ────────────────────────

    @Test
    @DisplayName("Valid XLSX with all fields parses correctly")
    void validXlsxAllFields() throws IOException {
        byte[] xlsxBytes = buildXlsx(
                new String[]{"name", "email", "phone", "source", "status", "notes", "tags"},
                new String[]{"Bob", "bob@example.com", "8765432109", "Referral", "INTERESTED", "Follow up", "hot"}
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes);

        List<BulkLeadRowDTO> rows = parser.parse(file);

        assertThat(rows).hasSize(1);
        BulkLeadRowDTO row = rows.get(0);
        assertThat(row.getRowNumber()).isEqualTo(1);
        assertThat(row.getName()).isEqualTo("Bob");
        assertThat(row.getEmail()).isEqualTo("bob@example.com");
        assertThat(row.getPhone()).isEqualTo("8765432109");
        assertThat(row.getSource()).isEqualTo("Referral");
        assertThat(row.getStatus()).isEqualTo("INTERESTED");
        assertThat(row.getNotes()).isEqualTo("Follow up");
        assertThat(row.getTags()).isEqualTo("hot");
    }

    // ── 3. Missing `name` column in CSV → HTTP 422 ───────────────────────

    @Test
    @DisplayName("CSV missing 'name' column throws 422 UNPROCESSABLE_ENTITY")
    void csvMissingNameColumnThrows422() {
        String csv = "email,phone\nalice@example.com,9876543210\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).containsIgnoringCase("name");
                });
    }

    // ── 4. File size > 5 MB → HTTP 413 ───────────────────────────────────

    @Test
    @DisplayName("File larger than 5 MB throws 413 PAYLOAD_TOO_LARGE")
    void oversizedFileThrows413() {
        // 5 MB + 1 byte
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.csv", "text/csv", oversized);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                });
    }

    // ── 5. CSV with only a header row → HTTP 400 ─────────────────────────

    @Test
    @DisplayName("CSV with header row only (no data) throws 400 BAD_REQUEST")
    void csvHeaderOnlyThrows400() {
        String csv = "name,email,phone\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).containsIgnoringCase("no data rows");
                });
    }

    // ── 6. Case-insensitive headers ───────────────────────────────────────

    @Test
    @DisplayName("Mixed-case headers in CSV are mapped correctly (case-insensitive)")
    void csvCaseInsensitiveHeaders() {
        // Headers with non-standard casing
        String csv = "Name,EMAIL,Phone\nCharlie,charlie@example.com,7654321098\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        List<BulkLeadRowDTO> rows = parser.parse(file);

        assertThat(rows).hasSize(1);
        BulkLeadRowDTO row = rows.get(0);
        assertThat(row.getName()).isEqualTo("Charlie");
        assertThat(row.getEmail()).isEqualTo("charlie@example.com");
        assertThat(row.getPhone()).isEqualTo("7654321098");
    }

    // ── 7. Unknown columns are silently ignored ───────────────────────────

    @Test
    @DisplayName("Unknown columns in CSV are silently ignored; known fields still parsed")
    void csvUnknownColumnsIgnored() {
        String csv = "name,email,unknown_col,phone\nDave,dave@example.com,IGNORED_VALUE,6543210987\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        List<BulkLeadRowDTO> rows = parser.parse(file);

        assertThat(rows).hasSize(1);
        BulkLeadRowDTO row = rows.get(0);
        assertThat(row.getName()).isEqualTo("Dave");
        assertThat(row.getEmail()).isEqualTo("dave@example.com");
        assertThat(row.getPhone()).isEqualTo("6543210987");
    }

    // ── 8. Row numbers are 1-based ────────────────────────────────────────

    @Test
    @DisplayName("Row numbers in output are 1-based (first data row = 1)")
    void csvRowNumbersAreOneBased() {
        String csv = "name,email\nEve,eve@example.com\nFrank,frank@example.com\nGrace,grace@example.com\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        List<BulkLeadRowDTO> rows = parser.parse(file);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getRowNumber()).isEqualTo(1);
        assertThat(rows.get(1).getRowNumber()).isEqualTo(2);
        assertThat(rows.get(2).getRowNumber()).isEqualTo(3);
    }

    // ── Helper: build a minimal in-memory XLSX ────────────────────────────

    /**
     * Creates an in-memory XLSX workbook with a single sheet.
     * Row 0 contains the supplied headers; row 1 contains the supplied data values.
     */
    private byte[] buildXlsx(String[] headers, String[] dataRow) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            var sheet = workbook.createSheet("Leads");

            // Header row (index 0)
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // Data row (index 1)
            var row = sheet.createRow(1);
            for (int i = 0; i < dataRow.length; i++) {
                row.createCell(i).setCellValue(dataRow[i]);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
