package com.chatcrmlite.backend.services.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTextExtractorTest {

    private DocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        DocumentCharsetDecoder decoder = new DocumentCharsetDecoder();
        ReflectionTestUtils.setField(decoder, "fallbackCharsetName", "ISO-8859-1");
        ReflectionTestUtils.setField(decoder, "maxControlCharRatio", 0.30);
        extractor = new DocumentTextExtractor(decoder, new ObjectMapper());
        ReflectionTestUtils.setField(extractor, "excelMaxRowsPerSheet", 100_000);
        ReflectionTestUtils.setField(extractor, "excelMaxSheets", 50);
        ReflectionTestUtils.setField(extractor, "csvMaxRows", 100_000);
        ReflectionTestUtils.setField(extractor, "maxExtractedChars", 5_000_000);
    }

    @Test
    void supportedExtensionsIncludeOfficeAndText() {
        assertTrue(extractor.isSupported("a.PDF"));
        assertTrue(extractor.isSupported("b.XLSX"));
        assertTrue(extractor.isSupported("c.CSV"));
        assertFalse(extractor.isSupported("x.rtf"));
        assertFalse(extractor.isSupported("x.png"));
        assertFalse(extractor.isSupported("x.exe"));
    }

    @Test
    void csvProducesSemanticRows() throws Exception {
        byte[] csv = "Name,Email,Status\nJohn,john@example.com,Lead\nSarah,sarah@example.com,Customer\n"
                .getBytes(StandardCharsets.UTF_8);
        String text = extractor.extract(csv, "leads.csv");
        assertTrue(text.contains("Name: John"));
        assertTrue(text.contains("Email: john@example.com"));
        assertTrue(text.contains("Status: Lead"));
        assertTrue(text.contains("Name: Sarah"));
    }

    @Test
    void txtUtf8Extracts() throws Exception {
        String text = extractor.extract("Hello RAG world".getBytes(StandardCharsets.UTF_8), "note.txt");
        assertEquals("Hello RAG world", text);
    }

    @Test
    void mdExtracts() throws Exception {
        String text = extractor.extract("# Title\nBody".getBytes(StandardCharsets.UTF_8), "doc.md");
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("Body"));
    }

    @Test
    void htmlStripsScriptAndStyle() throws Exception {
        String html = "<html><head><style>.x{}</style><script>alert(1)</script></head>"
                + "<body><p>Visible text</p></body></html>";
        String text = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "page.html");
        assertTrue(text.contains("Visible text"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains(".x{}"));
    }

    @Test
    void jsonFlattensNestedPaths() throws Exception {
        String json = "{\"customer\":{\"name\":\"John\",\"status\":\"lead\"},"
                + "\"items\":[{\"product\":\"A\",\"quantity\":3}]}";
        String text = extractor.extract(json.getBytes(StandardCharsets.UTF_8), "data.json");
        assertTrue(text.contains("customer.name: John"));
        assertTrue(text.contains("customer.status: lead"));
        assertTrue(text.contains("items[0].product: A"));
        assertTrue(text.contains("items[0].quantity: 3"));
    }

    @Test
    void xlsxMultiSheetAndFormulaCalculatedValue() throws Exception {
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Customers");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Total");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("John");
            r1.createCell(1).setCellValue(100);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("Sum");
            r2.createCell(1).setCellFormula("B2+200");

            Sheet sheet2 = wb.createSheet("Notes");
            sheet2.createRow(0).createCell(0).setCellValue("Note");
            sheet2.createRow(1).createCell(0).setCellValue("Hello");

            wb.write(bos);
            xlsx = bos.toByteArray();
        }

        String text = extractor.extract(xlsx, "book.XLSX");
        assertTrue(text.contains("Sheet: Customers"));
        assertTrue(text.contains("Name: John"));
        assertTrue(text.contains("300") || text.contains("Total: 300"),
                "Expected calculated formula value 300, got: " + text);
        assertTrue(text.contains("Sheet: Notes"));
        assertTrue(text.contains("Hello"));
    }

    @Test
    void xlsClassicWorkbook() throws Exception {
        byte[] xls;
        try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("S1");
            sheet.createRow(0).createCell(0).setCellValue("Col");
            sheet.createRow(1).createCell(0).setCellValue("Val");
            wb.write(bos);
            xls = bos.toByteArray();
        }
        String text = extractor.extract(xls, "old.xls");
        assertTrue(text.contains("Col"));
        assertTrue(text.contains("Val"));
    }

    @Test
    void rtfRejected() {
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract("{\\rtf1 hi}".getBytes(StandardCharsets.UTF_8), "a.rtf"));
        assertEquals(DocumentExtractionErrorCode.UNSUPPORTED_FORMAT, ex.getCode());
    }

    @Test
    void pngRejected() {
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, "a.png"));
        assertEquals(DocumentExtractionErrorCode.UNSUPPORTED_FORMAT, ex.getCode());
    }

    @Test
    void emptyFileRejected() {
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(new byte[0], "a.txt"));
        assertEquals(DocumentExtractionErrorCode.EMPTY_DOCUMENT, ex.getCode());
    }

    @Test
    void renamedBinaryAsPdfMalformed() {
        byte[] junk = new byte[64];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 17);
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(junk, "fake.pdf"));
        assertEquals(DocumentExtractionErrorCode.MALFORMED_FILE, ex.getCode());
    }

    @Test
    void renamedBinaryAsCsvFailsPlausibility() {
        byte[] junk = new byte[200];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) ((i * 37) % 256);
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(junk, "fake.csv"));
        assertEquals(DocumentExtractionErrorCode.MALFORMED_FILE, ex.getCode());
    }

    @Test
    void overCharLimitFailsWithoutPartial() {
        ReflectionTestUtils.setField(extractor, "maxExtractedChars", 20);
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract("This text is definitely longer than twenty chars"
                        .getBytes(StandardCharsets.UTF_8), "a.txt"));
        assertEquals(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED, ex.getCode());
    }

    @Test
    void csvRowLimitFailsEntireFile() {
        ReflectionTestUtils.setField(extractor, "csvMaxRows", 2);
        StringBuilder sb = new StringBuilder("A,B\n");
        for (int i = 0; i < 5; i++) sb.append("x,y\n");
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(sb.toString().getBytes(StandardCharsets.UTF_8), "big.csv"));
        assertEquals(DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED, ex.getCode());
    }
}
