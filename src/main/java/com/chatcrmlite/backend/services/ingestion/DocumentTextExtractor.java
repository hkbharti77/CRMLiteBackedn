package com.chatcrmlite.backend.services.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for RAG upload format support and text extraction.
 */
@Slf4j
@Service
public class DocumentTextExtractor {

    private static final Set<String> SUPPORTED = Set.of(
            "pdf", "docx", "xlsx", "xls", "csv",
            "txt", "md", "markdown", "html", "htm", "json"
    );

    private final DocumentCharsetDecoder charsetDecoder;
    private final ObjectMapper objectMapper;

    @Value("${rag.ingestion.excel.max-rows-per-sheet:100000}")
    private int excelMaxRowsPerSheet = 100_000;

    @Value("${rag.ingestion.excel.max-sheets:50}")
    private int excelMaxSheets = 50;

    @Value("${rag.ingestion.csv.max-rows:100000}")
    private int csvMaxRows = 100_000;

    @Value("${rag.ingestion.max-extracted-chars:5000000}")
    private int maxExtractedChars = 5_000_000;

    public DocumentTextExtractor(DocumentCharsetDecoder charsetDecoder, ObjectMapper objectMapper) {
        this.charsetDecoder = charsetDecoder;
        this.objectMapper = objectMapper;
    }

    public boolean isSupported(String filename) {
        String ext = extension(filename);
        return ext != null && SUPPORTED.contains(ext);
    }

    public Set<String> getSupportedExtensions() {
        return SUPPORTED.stream()
                .map(e -> "." + e)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String extract(byte[] bytes, String filename) throws DocumentExtractionException {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "Empty file");
        }
        String ext = extension(filename);
        if (ext == null || !SUPPORTED.contains(ext)) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.UNSUPPORTED_FORMAT,
                    "Unsupported format. Allowed: " + String.join(", ", getSupportedExtensions()));
        }

        try {
            String text = switch (ext) {
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "xlsx" -> extractExcel(bytes, true);
                case "xls" -> extractExcel(bytes, false);
                case "csv" -> extractCsv(bytes);
                case "txt", "md", "markdown" -> extractPlainText(bytes);
                case "html", "htm" -> extractHtml(bytes);
                case "json" -> extractJson(bytes);
                default -> throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.UNSUPPORTED_FORMAT, "Unsupported: ." + ext);
            };
            return finalizeText(text);
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.PARSER_FAILURE,
                    "Failed to extract text from ." + ext + ": " + e.getMessage(), e);
        }
    }

    private String finalizeText(String text) throws DocumentExtractionException {
        if (text == null || text.isBlank()) {
            throw new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "No extractable text");
        }
        if (text.length() > maxExtractedChars) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                    "Extracted text exceeds max-extracted-chars=" + maxExtractedChars
                            + " (got " + text.length() + "). Document rejected; no partial ingest.");
        }
        return text;
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(bytes))) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid or unreadable PDF", e);
        }
    }

    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid or unreadable DOCX", e);
        }
    }

    private String extractExcel(byte[] bytes, boolean ooxml) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = ooxml
                ? new XSSFWorkbook(new ByteArrayInputStream(bytes))
                : new HSSFWorkbook(new ByteArrayInputStream(bytes))) {

            int sheetCount = workbook.getNumberOfSheets();
            if (sheetCount > excelMaxSheets) {
                throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                        "Excel sheet count " + sheetCount + " exceeds max-sheets=" + excelMaxSheets);
            }

            StringBuilder out = new StringBuilder();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                out.append("Sheet: ").append(sheet.getSheetName()).append("\n");

                Iterator<Row> rowIterator = sheet.iterator();
                List<String> headers = null;
                int dataRows = 0;
                int physicalRowsSeen = 0;

                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    physicalRowsSeen++;
                    if (physicalRowsSeen > excelMaxRowsPerSheet + 1) {
                        throw new DocumentExtractionException(
                                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                                "Excel sheet '" + sheet.getSheetName() + "' exceeds max-rows-per-sheet="
                                        + excelMaxRowsPerSheet);
                    }

                    List<String> cells = readRow(row, formatter, evaluator);
                    if (isBlankRow(cells)) {
                        continue;
                    }

                    if (headers == null) {
                        headers = cells;
                        out.append("Columns: ").append(String.join(" | ", headers)).append("\n");
                        continue;
                    }

                    dataRows++;
                    if (dataRows > excelMaxRowsPerSheet) {
                        throw new DocumentExtractionException(
                                DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                                "Excel sheet '" + sheet.getSheetName() + "' exceeds max-rows-per-sheet="
                                        + excelMaxRowsPerSheet);
                    }

                    out.append("Row ").append(row.getRowNum() + 1).append(": ");
                    out.append(formatNamedCells(headers, cells)).append("\n");
                }
                out.append("\n");
            }
            return out.toString().trim();
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE,
                    "Invalid or unreadable Excel (" + (ooxml ? "xlsx" : "xls") + ")", e);
        }
    }

    private List<String> readRow(Row row, DataFormatter formatter,
                                 org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        short last = row.getLastCellNum();
        if (last < 0) return List.of();
        List<String> cells = new ArrayList<>(last);
        for (int i = 0; i < last; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String v = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
            cells.add(v != null ? v.trim() : "");
        }
        return cells;
    }

    private static boolean isBlankRow(List<String> cells) {
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    private static String formatNamedCells(List<String> headers, List<String> cells) {
        List<String> parts = new ArrayList<>();
        int n = Math.max(headers.size(), cells.size());
        for (int i = 0; i < n; i++) {
            String header = i < headers.size() && headers.get(i) != null && !headers.get(i).isBlank()
                    ? headers.get(i) : ("Column" + (i + 1));
            String value = i < cells.size() ? cells.get(i) : "";
            parts.add(header + ": " + value);
        }
        return String.join(" | ", parts);
    }

    private String extractCsv(byte[] bytes) throws Exception {
        String raw = charsetDecoder.decode(bytes);
        charsetDecoder.assertPlausibleText(raw);

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(raw))) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                // No header — treat first parse differently: re-parse without header
                return extractCsvWithoutHeader(raw);
            }

            List<String> headers = new ArrayList<>(headerMap.keySet());

            StringBuilder out = new StringBuilder();
            out.append("Columns: ").append(String.join(" | ", headers)).append("\n");
            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > csvMaxRows) {
                    throw new DocumentExtractionException(
                            DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                            "CSV exceeds max-rows=" + csvMaxRows);
                }
                List<String> parts = new ArrayList<>();
                for (String header : headers) {
                    String value = record.isMapped(header) ? record.get(header) : "";
                    parts.add(header + ": " + (value != null ? value : ""));
                }
                out.append("Row ").append(rows + 1).append(": ")
                        .append(String.join(" | ", parts)).append("\n");
            }
            return out.toString().trim();
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid CSV content", e);
        }
    }

    private String extractCsvWithoutHeader(String raw) throws DocumentExtractionException {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new StringReader(raw))) {
            StringBuilder out = new StringBuilder();
            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > csvMaxRows) {
                    throw new DocumentExtractionException(
                            DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                            "CSV exceeds max-rows=" + csvMaxRows);
                }
                if (rows == 1) {
                    List<String> synthetic = new ArrayList<>();
                    for (int i = 0; i < record.size(); i++) {
                        synthetic.add("Column" + (i + 1));
                    }
                    out.append("Columns: ").append(String.join(" | ", synthetic)).append("\n");
                }
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < record.size(); i++) {
                    parts.add("Column" + (i + 1) + ": " + record.get(i));
                }
                out.append("Row ").append(rows).append(": ")
                        .append(String.join(" | ", parts)).append("\n");
            }
            return out.toString().trim();
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid CSV content", e);
        }
    }

    private String extractPlainText(byte[] bytes) throws DocumentExtractionException {
        String text = charsetDecoder.decode(bytes);
        charsetDecoder.assertPlausibleText(text);
        return text;
    }

    private String extractHtml(byte[] bytes) throws DocumentExtractionException {
        String raw = charsetDecoder.decode(bytes);
        charsetDecoder.assertPlausibleText(raw);
        try {
            Document doc = Jsoup.parse(raw);
            doc.select("script, style, noscript").remove();
            String text = doc.body() != null ? doc.body().text() : doc.text();
            charsetDecoder.assertPlausibleText(text);
            return text;
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid HTML content", e);
        }
    }

    private String extractJson(byte[] bytes) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            StringBuilder out = new StringBuilder();
            flattenJson("", root, out);
            return out.toString().trim();
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE, "Invalid JSON content", e);
        }
    }

    private void flattenJson(String path, JsonNode node, StringBuilder out) throws DocumentExtractionException {
        if (out.length() > maxExtractedChars) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.EXTRACTION_LIMIT_EXCEEDED,
                    "JSON flattened text exceeds max-extracted-chars=" + maxExtractedChars);
        }
        if (node == null || node.isNull()) {
            out.append(path.isEmpty() ? "value" : path).append(": null\n");
            return;
        }
        if (node.isValueNode()) {
            out.append(path.isEmpty() ? "value" : path).append(": ").append(node.asText()).append("\n");
            return;
        }
        if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                String childPath = path + "[" + i + "]";
                flattenJson(childPath, child, out);
                i++;
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                flattenJson(childPath, e.getValue(), out);
            }
        }
    }

    static String extension(String filename) {
        if (filename == null || filename.isBlank()) return null;
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
