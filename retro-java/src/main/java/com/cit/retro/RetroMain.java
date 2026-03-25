package com.cit.retro;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retro CLI - Batch trade submission and verification tool for CIT mocknet.
 *
 * Usage: java -jar retro.jar <input_file> [options]
 */
public class RetroMain {

    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final String DEFAULT_DB = "mocknet/data/coredb";
    private static final Set<String> TERMINAL_STATUSES = Set.of("NETTED", "SETTLED", "REJECTED");

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return;
        }

        // Parse arguments
        String inputFile = args[0];
        String url = DEFAULT_URL;
        String dbPath = DEFAULT_DB;
        String outputFile = null;
        boolean inPlace = false;
        boolean noHeader = false;
        boolean skipDb = false;
        int timeout = 30;
        double delay = 0.5;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--db" -> dbPath = args[++i];
                case "--output" -> outputFile = args[++i];
                case "--in-place" -> inPlace = true;
                case "--no-header" -> noHeader = true;
                case "--skip-db" -> skipDb = true;
                case "--timeout" -> timeout = Integer.parseInt(args[++i]);
                case "--delay" -> delay = Double.parseDouble(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                }
            }
        }

        // Validate input file
        int dot = inputFile.lastIndexOf('.');
        String ext = dot >= 0 ? inputFile.substring(dot).toLowerCase() : "";
        if (!".csv".equals(ext) && !".xlsx".equals(ext)) {
            System.err.println("Error: Expected .xlsx or .csv file, got '" + ext + "'");
            System.exit(1);
        }

        if (!java.nio.file.Files.exists(Path.of(inputFile))) {
            System.err.println("Error: File not found: " + inputFile);
            System.exit(1);
        }

        // Resolve DB path
        dbPath = Path.of(dbPath).toAbsolutePath().toString();
        if (dbPath.endsWith(".mv.db")) {
            dbPath = dbPath.substring(0, dbPath.length() - 6);
        }

        try {
            run(inputFile, ext, url, dbPath, outputFile, inPlace, noHeader, skipDb, timeout, delay);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String inputFile, String ext, String url, String dbPath, String outputFile,
                            boolean inPlace, boolean noHeader, boolean skipDb,
                            int timeout, double delay) throws Exception {

        // Read input
        List<FileHandler.TradeRow> rows;
        if (".csv".equals(ext)) {
            rows = FileHandler.readCsv(inputFile, noHeader);
        } else {
            rows = FileHandler.readXlsx(inputFile, noHeader);
        }

        if (rows.isEmpty()) {
            System.err.println("No data rows found in input file.");
            System.exit(1);
        }

        // Initialize components
        TradeSubmitter submitter = new TradeSubmitter(url);
        DbVerifier dbVerifier = null;

        if (!skipDb) {
            dbVerifier = new DbVerifier(dbPath);
            if (dbVerifier.isAvailable()) {
                System.out.println("H2 database found: " + dbPath);
            } else {
                System.out.println("Warning: Database not found at " + dbPath + ".mv.db. DB verification disabled.");
                dbVerifier = null;
            }
        }

        int total = rows.size();
        int passCount = 0, failCount = 0, skipCount = 0;
        Map<Integer, String> pendingVerification = new LinkedHashMap<>();

        System.out.println("\nProcessing " + total + " trades -> " + url);
        System.out.println("-".repeat(60));

        for (int i = 0; i < total; i++) {
            FileHandler.TradeRow row = rows.get(i);

            if (row.payload.isEmpty()) {
                row.response = "No payload";
                row.result = "SKIP";
                skipCount++;
                printRow(i + 1, total, row);
                continue;
            }

            // Submit trade
            String[] httpResult = submitter.submit(row.payload);
            int statusCode = Integer.parseInt(httpResult[0]);
            String responseBody = httpResult[1];

            if (statusCode == -1) {
                // Connection error
                row.response = responseBody;
                row.result = "FAIL";
                failCount++;
                printRow(i + 1, total, row);
                continue;
            }

            if (statusCode != 202) {
                row.response = "HTTP " + statusCode + ": " + responseBody;
                row.result = "FAIL";
                failCount++;
                printRow(i + 1, total, row);
                continue;
            }

            String tradeId = extractTradeId(row.payload);
            if (tradeId != null && dbVerifier != null) {
                pendingVerification.put(i, tradeId);
                row.response = "HTTP 202 Accepted | DB: PENDING";
                row.result = "PENDING";
                System.out.printf("  [%d/%d] %s -> \033[34mQUEUED\033[0m (%s)%n",
                        i + 1, total, row.participant, row.response);
            } else {
                row.response = "HTTP 202 Accepted | DB: N/A" + (tradeId == null ? " (no tradeId)" : " (no DB)");
                row.result = "PASS";
                passCount++;
                printRow(i + 1, total, row);
            }

            if (i < total - 1) {
                Thread.sleep((long) (delay * 1000));
            }
        }

        for (Map.Entry<Integer, String> pending : pendingVerification.entrySet()) {
            int rowIndex = pending.getKey();
            FileHandler.TradeRow row = rows.get(rowIndex);
            String dbStatus = pollTradeStatus(dbVerifier, submitter, pending.getValue(), timeout);

            if ("TIMEOUT".equals(dbStatus)) {
                row.response = "HTTP 202 Accepted | DB: TIMEOUT";
                row.result = "FAIL";
                failCount++;
            } else if (DbVerifier.isPass(dbStatus)) {
                row.response = "HTTP 202 Accepted | DB: " + dbStatus;
                row.result = "PASS";
                passCount++;
            } else {
                row.response = "HTTP 202 Accepted | DB: " + dbStatus;
                row.result = "FAIL";
                failCount++;
            }

            printVerificationRow(row, rowIndex + 1, total);
        }

        // Write output
        String outFile = FileHandler.outputPath(inputFile, inPlace, outputFile);
        String outExt = extensionOf(outFile);
        if (!".csv".equals(outExt) && !".xlsx".equals(outExt)) {
            System.err.println("Error: Output file must end in .csv or .xlsx, got '" + outExt + "'");
            System.exit(1);
        }

        if (".csv".equals(outExt)) {
            FileHandler.writeCsv(outFile, rows, noHeader);
        } else {
            if (".xlsx".equals(ext)) {
                FileHandler.writeXlsx(inputFile, outFile, rows, noHeader);
            } else {
                FileHandler.writeXlsxFromRows(outFile, rows, noHeader);
            }
        }

        System.out.println("-".repeat(60));
        System.out.printf("Results: %d PASS, %d FAIL, %d SKIP out of %d trades%n", passCount, failCount, skipCount, total);
        System.out.println("Written to: " + outFile);
    }

    private static String extractTradeId(String xmlPayload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entities for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlPayload)));
            NodeList nodes = doc.getElementsByTagName("tradeId");
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                return text != null ? text.trim() : null;
            }
        } catch (Exception e) {
            // Can't parse XML - return null
        }
        return null;
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot).toLowerCase() : "";
    }

    private static void printRow(int idx, int total, FileHandler.TradeRow row) {
        String color = switch (row.result) {
            case "PASS" -> "\033[32m";
            case "SKIP" -> "\033[33m";
            default -> "\033[31m";
        };
        System.out.printf("  [%d/%d] %s -> %s%s\033[0m (%s)%n",
                idx, total, row.participant, color, row.result, row.response);
    }

    private static void printVerificationRow(FileHandler.TradeRow row, int idx, int total) {
        String color = "PASS".equals(row.result) ? "\033[32m" : "\033[31m";
        System.out.printf("  [verify %d/%d] %s -> %s%s\033[0m (%s)%n",
                idx, total, row.participant, color, row.result, row.response);
    }

    private static String pollTradeStatus(DbVerifier dbVerifier, TradeSubmitter submitter, String tradeId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            String status = dbVerifier == null ? null : dbVerifier.queryTradeStatus(tradeId);
            if (status == null) {
                status = submitter.fetchTradeStatus(tradeId);
            }
            if (status != null && TERMINAL_STATUSES.contains(status)) {
                return status;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "TIMEOUT";
            }
        }

        return "TIMEOUT";
    }

    private static void printHelp() {
        System.out.println("""
                Retro CLI - Batch trade submission and verification tool for CIT mocknet.

                Usage: java -jar retro.jar <input_file> [options]

                Options:
                  --url URL          Mocknet base URL (default: http://localhost:8080)
                  --db PATH          H2 database file path without extension (default: mocknet/data/coredb)
                  --output FILE      Write results to a specific .csv or .xlsx file
                  --in-place         Overwrite input file instead of creating _results file
                  --no-header        Positional columns: A=participant, B=payload, C=response, D=result
                  --skip-db          Skip DB verification, only check HTTP response
                  --timeout SECONDS  Per-trade DB verification timeout (default: 30)
                  --delay SECONDS    Delay between submissions (default: 0.5)
                """);
    }
}
