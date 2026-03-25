package com.cit.retro;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads and writes Excel (.xlsx) and CSV files.
 */
public class FileHandler {

    public static final String[] EXPECTED_HEADERS = {"participant", "payload", "response", "result"};

    /**
     * A single row from the input file.
     */
    public static class TradeRow {
        public String participant;
        public String payload;
        public String response;
        public String result;
        public int rowIndex; // 0-based data row index

        public TradeRow(String participant, String payload, int rowIndex) {
            this.participant = participant != null ? participant.trim() : "";
            this.payload = payload != null ? payload.trim() : "";
            this.response = "";
            this.result = "";
            this.rowIndex = rowIndex;
        }
    }

    // --- CSV ---

    public static List<TradeRow> readCsv(String filePath, boolean noHeader) throws IOException {
        List<TradeRow> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new FileReader(filePath))) {
            String[] line;
            int idx = 0;

            if (!noHeader) {
                String[] header = reader.readNext();
                if (header == null) throw new IOException("Empty CSV file");
                Map<String, Integer> colMap = headerMap(header);
                if (!colMap.containsKey("participant") || !colMap.containsKey("payload")) {
                    throw new IOException("CSV must have 'participant' and 'payload' headers. Found: " + Arrays.toString(header));
                }

                while ((line = reader.readNext()) != null) {
                    String participant = safeGet(line, colMap.getOrDefault("participant", 0));
                    String payload = safeGet(line, colMap.getOrDefault("payload", 1));
                    if (!participant.isEmpty() || !payload.isEmpty()) {
                        rows.add(new TradeRow(participant, payload, idx++));
                    }
                }
            } else {
                while ((line = reader.readNext()) != null) {
                    if (line.length >= 2) {
                        rows.add(new TradeRow(line[0], line[1], idx++));
                    }
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("Failed to parse CSV file: " + filePath, e);
        }
        return rows;
    }

    public static void writeCsv(String filePath, List<TradeRow> rows, boolean noHeader) throws IOException {
        try (CSVWriter writer = new CSVWriter(new FileWriter(filePath))) {
            if (!noHeader) {
                writer.writeNext(EXPECTED_HEADERS);
            }
            for (TradeRow row : rows) {
                writer.writeNext(new String[]{row.participant, row.payload, row.response, row.result});
            }
        }
    }

    // --- Excel ---

    public static List<TradeRow> readXlsx(String filePath, boolean noHeader) throws IOException {
        List<TradeRow> rows = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IOException("No sheets found in Excel file");

            if (noHeader) {
                for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    String participant = cellToString(row.getCell(0));
                    String payload = cellToString(row.getCell(1));
                    if (!participant.isEmpty() || !payload.isEmpty()) {
                        rows.add(new TradeRow(participant, payload, i));
                    }
                }
            } else {
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) throw new IOException("Empty Excel file");
                Map<String, Integer> colMap = new HashMap<>();
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    String h = cellToString(headerRow.getCell(c)).toLowerCase().trim();
                    colMap.put(h, c);
                }
                if (!colMap.containsKey("participant") || !colMap.containsKey("payload")) {
                    throw new IOException("Excel must have 'participant' and 'payload' headers. Found: " + colMap.keySet());
                }

                int pCol = colMap.get("participant");
                int plCol = colMap.get("payload");

                for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    String participant = cellToString(row.getCell(pCol));
                    String payload = cellToString(row.getCell(plCol));
                    if (!participant.isEmpty() || !payload.isEmpty()) {
                        rows.add(new TradeRow(participant, payload, i));
                    }
                }
            }
        }
        return rows;
    }

    public static void writeXlsx(String inputPath, String outputPath, List<TradeRow> rows, boolean noHeader) throws IOException {
        try (FileInputStream fis = new FileInputStream(inputPath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);

            if (noHeader) {
                for (TradeRow tr : rows) {
                    Row row = sheet.getRow(tr.rowIndex);
                    if (row == null) row = sheet.createRow(tr.rowIndex);
                    setCellValue(row, 2, tr.response);
                    setCellValue(row, 3, tr.result);
                }
            } else {
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                Map<String, Integer> colMap = new HashMap<>();
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    colMap.put(cellToString(headerRow.getCell(c)).toLowerCase().trim(), c);
                }

                int nextCol = Math.max((int) headerRow.getLastCellNum(), 0);
                int respCol = colMap.getOrDefault("response", nextCol);
                int resCol = colMap.getOrDefault("result", respCol + 1);

                // Ensure headers exist
                if (!colMap.containsKey("response")) {
                    setCellValue(headerRow, respCol, "response");
                }
                if (!colMap.containsKey("result")) {
                    setCellValue(headerRow, resCol, "result");
                }

                for (TradeRow tr : rows) {
                    Row row = sheet.getRow(tr.rowIndex);
                    if (row == null) row = sheet.createRow(tr.rowIndex);
                    setCellValue(row, respCol, tr.response);
                    setCellValue(row, resCol, tr.result);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                wb.write(fos);
            }
        }
    }

    public static void writeXlsxFromRows(String outputPath, List<TradeRow> rows, boolean noHeader) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("trades");

            int rowIdx = 0;
            if (!noHeader) {
                Row headerRow = sheet.createRow(rowIdx++);
                for (int i = 0; i < EXPECTED_HEADERS.length; i++) {
                    setCellValue(headerRow, i, EXPECTED_HEADERS[i]);
                }
            }

            for (TradeRow tradeRow : rows) {
                Row row = sheet.createRow(rowIdx++);
                setCellValue(row, 0, tradeRow.participant);
                setCellValue(row, 1, tradeRow.payload);
                setCellValue(row, 2, tradeRow.response);
                setCellValue(row, 3, tradeRow.result);
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                wb.write(fos);
            }
        }
    }

    // --- Helpers ---

    private static Map<String, Integer> headerMap(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].toLowerCase().trim(), i);
        }
        return map;
    }

    private static String safeGet(String[] arr, int idx) {
        return idx < arr.length ? (arr[idx] != null ? arr[idx].trim() : "") : "";
    }

    private static String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static void setCellValue(Row row, int colIdx, String value) {
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    /**
     * Compute output file path: input_results.ext or same as input if in-place.
     */
    public static String outputPath(String inputPath, boolean inPlace, String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) return outputPath;
        if (inPlace) return inputPath;
        Path p = Path.of(inputPath);
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String outputName;
        if (dot > 0) {
            outputName = name.substring(0, dot) + "_results" + name.substring(dot);
        } else {
            outputName = name + "_results";
        }
        return p.resolveSibling(outputName).toString();
    }
}
