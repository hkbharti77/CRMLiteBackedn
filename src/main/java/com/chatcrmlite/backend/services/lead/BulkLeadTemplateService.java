package com.chatcrmlite.backend.services.lead;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates downloadable lead import templates in XLSX and CSV formats.
 *
 * <p>Design §7 — Template Generation
 * <p>Requirement 2 — Template Download
 */
@Service
public class BulkLeadTemplateService {

    private static final String[] HEADERS = {
            "name", "email", "phone", "source", "status", "notes", "tags"
    };

    private static final String[] EXAMPLE_ROW = {
            "John Doe", "john@example.com", "9999999999", "WhatsApp", "NEW", "", ""
    };

    /**
     * Generates an XLSX template with a header row and one example data row.
     *
     * @return byte array of the generated .xlsx file
     * @throws RuntimeException wrapping any {@link IOException} from workbook I/O
     */
    public byte[] generateXlsx() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Leads");

            // Row 0 — headers
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                headerRow.createCell(i).setCellValue(HEADERS[i]);
            }

            // Row 1 — example data
            XSSFRow exampleRow = sheet.createRow(1);
            for (int i = 0; i < EXAMPLE_ROW.length; i++) {
                exampleRow.createCell(i).setCellValue(EXAMPLE_ROW[i]);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate XLSX template", e);
        }
    }

    /**
     * Returns a static CSV template string with a header row and one example data row.
     *
     * @return CSV content as a plain {@link String}
     */
    public String generateCsv() {
        return "name,email,phone,source,status,notes,tags\n"
                + "John Doe,john@example.com,9999999999,WhatsApp,NEW,,";
    }
}
