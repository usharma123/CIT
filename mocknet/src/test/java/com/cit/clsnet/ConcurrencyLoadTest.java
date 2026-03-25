package com.cit.clsnet;

import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "clsnet.threads.ingestion=4",
                "clsnet.threads.matching=4",
                "clsnet.threads.netting=4",
                "clsnet.threads.settlement=2",
                "spring.datasource.hikari.maximum-pool-size=20"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrencyLoadTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String[] BANKS = {
            "GOLDMAN", "MORGAN", "JPMORGAN", "CITI", "BARCLAYS",
            "HSBC", "DEUTSCHE", "UBS", "BNP", "SOCGEN",
            "NOMURA", "MIZUHO", "BOFA", "WELLS", "RBC",
            "TD", "ANZ", "NAB", "STAN", "ING"
    };

    private static final String[][] CURRENCY_PAIRS = {
            {"USD", "EUR"}, {"USD", "JPY"}, {"USD", "GBP"}, {"USD", "CHF"},
            {"EUR", "GBP"}, {"EUR", "JPY"}, {"EUR", "CHF"}, {"GBP", "JPY"},
            {"AUD", "USD"}, {"NZD", "USD"}, {"USD", "CAD"}, {"USD", "SEK"},
            {"EUR", "NOK"}, {"EUR", "DKK"}, {"USD", "SGD"}, {"USD", "HKD"},
            {"USD", "KRW"}, {"USD", "ZAR"}, {"USD", "MXN"}, {"USD", "BRL"}
    };

    private static final int BASELINE_NUM_PAIRS = 100;
    private static final int LARGE_NUM_PAIRS = 500;

    @Test
    void loadTest_100TradePairs_allProcessedCorrectly() throws Exception {
        runLoadTest(BASELINE_NUM_PAIRS, 20, 30);
    }

    @Test
    void loadTest_1000Payloads_allProcessedCorrectly() throws Exception {
        runLoadTest(LARGE_NUM_PAIRS, 40, 60);
    }

    private void runLoadTest(int numPairs, int submitterThreads, int maxWaitSeconds) throws Exception {
        List<String> allXmlPayloads = new ArrayList<>();

        // Generate paired trades so every payload has a deterministic counterpart.
        for (int i = 0; i < numPairs; i++) {
            int bankIdx1 = i % BANKS.length;
            int bankIdx2 = (i + 1) % BANKS.length;
            String bank1 = BANKS[bankIdx1];
            String bank2 = BANKS[bankIdx2];
            String[] ccyPair = CURRENCY_PAIRS[i % CURRENCY_PAIRS.length];
            String ccy1 = ccyPair[0];
            String ccy2 = ccyPair[1];
            double amount1 = 1000000.0 + (i * 10000);
            double amount2 = amount1 * 0.92;
            String valueDate = String.format("2026-04-%02d", (i % 28) + 1);

            // Buy-side trade
            allXmlPayloads.add(buildTradeXml(
                    "MSG-L" + (i * 2 + 1), "TRD-L" + (i * 2 + 1), "SPOT",
                    bank1, "BUYER", bank2, "SELLER",
                    ccy1, String.format("%.2f", amount1),
                    ccy2, String.format("%.2f", amount2),
                    "1.0869565", valueDate));

            // Sell-side trade
            allXmlPayloads.add(buildTradeXml(
                    "MSG-L" + (i * 2 + 2), "TRD-L" + (i * 2 + 2), "SPOT",
                    bank2, "SELLER", bank1, "BUYER",
                    ccy1, String.format("%.2f", amount1),
                    ccy2, String.format("%.2f", amount2),
                    "1.0869565", valueDate));
        }

        // Fire all 200 trades concurrently
        ExecutorService submitter = Executors.newFixedThreadPool(submitterThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(allXmlPayloads.size());
        AtomicInteger acceptedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        for (String xml : allXmlPayloads) {
            submitter.submit(() -> {
                try {
                    startGate.await(); // Wait for all threads to be ready
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_XML);
                    ResponseEntity<Map> resp = restTemplate.exchange(
                            "/api/trades", HttpMethod.POST,
                            new HttpEntity<>(xml, headers), Map.class);
                    if (resp.getStatusCode() == HttpStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    } else {
                        failedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        long startTime = System.currentTimeMillis();
        startGate.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All submissions should complete within 30s");
        long submissionTime = System.currentTimeMillis() - startTime;

        assertEquals(numPairs * 2, acceptedCount.get(), "All trades should be accepted");
        assertEquals(0, failedCount.get(), "No submissions should fail");

        // Poll for pipeline completion
        boolean pipelineComplete = false;
        for (int i = 0; i < maxWaitSeconds * 10; i++) {
            Thread.sleep(100);
            ResponseEntity<Map> statusResp = restTemplate.getForEntity("/api/status", Map.class);
            Map<String, Object> body = statusResp.getBody();
            int settledCount = ((Number) body.get("settlementInstructions")).intValue();
            Map<String, Object> queues = (Map<String, Object>) body.get("queues");
            Map<String, Object> ingestionQueue = (Map<String, Object>) queues.get("INGESTION");
            Map<String, Object> matchingQueue = (Map<String, Object>) queues.get("MATCHING");
            Map<String, Object> nettingQueue = (Map<String, Object>) queues.get("NETTING");

            boolean queuesDrained =
                    ((Number) ingestionQueue.get("DONE")).intValue() == numPairs * 2
                            && ((Number) matchingQueue.get("DONE")).intValue() == numPairs * 2
                            && ((Number) nettingQueue.get("DONE")).intValue() == numPairs
                            && ((Number) ingestionQueue.get("NEW")).intValue() == 0
                            && ((Number) ingestionQueue.get("PROCESSING")).intValue() == 0
                            && ((Number) matchingQueue.get("NEW")).intValue() == 0
                            && ((Number) matchingQueue.get("PROCESSING")).intValue() == 0
                            && ((Number) nettingQueue.get("NEW")).intValue() == 0
                            && ((Number) nettingQueue.get("PROCESSING")).intValue() == 0;

            if (settledCount == numPairs * 2 && queuesDrained) {
                pipelineComplete = true;
                break;
            }
        }
        long totalTime = System.currentTimeMillis() - startTime;

        // Final verification
        ResponseEntity<Map> statusResp = restTemplate.getForEntity("/api/status", Map.class);
        Map<String, Object> status = statusResp.getBody();
        assertNotNull(status);

        int totalTrades = ((Number) status.get("totalTrades")).intValue();
        int matchedTrades = ((Number) status.get("matchedTrades")).intValue();
        int nettingSets = ((Number) status.get("nettingSets")).intValue();
        int settlementInstructions = ((Number) status.get("settlementInstructions")).intValue();

        Map<String, Object> twopc = (Map<String, Object>) status.get("twoPhaseCommitTransactions");
        int committed = ((Number) twopc.get("COMMITTED")).intValue();
        int aborted = twopc.containsKey("ABORTED") ? ((Number) twopc.get("ABORTED")).intValue() : 0;
        Map<String, Object> queues = (Map<String, Object>) status.get("queues");
        Map<String, Object> ingestionQueue = (Map<String, Object>) queues.get("INGESTION");
        Map<String, Object> matchingQueue = (Map<String, Object>) queues.get("MATCHING");
        Map<String, Object> nettingQueue = (Map<String, Object>) queues.get("NETTING");

        // Log results
        System.out.println("=== CONCURRENCY LOAD TEST RESULTS ===");
        System.out.println("Trades submitted:        " + acceptedCount.get());
        System.out.println("Submission time:         " + submissionTime + "ms");
        System.out.println("Total pipeline time:     " + totalTime + "ms");
        System.out.println("Trades ingested:         " + totalTrades);
        System.out.println("Matched pairs:           " + matchedTrades);
        System.out.println("Netting sets:            " + nettingSets);
        System.out.println("Settlement instructions: " + settlementInstructions);
        System.out.println("2PC committed:           " + committed);
        System.out.println("2PC aborted:             " + aborted);
        System.out.println("Throughput:              " + (totalTrades * 1000L / Math.max(totalTime, 1)) + " trades/sec");
        System.out.println("=====================================");

        // Assertions
        assertTrue(pipelineComplete, "Pipeline should reach fully drained terminal queue states");
        assertEquals(numPairs * 2, totalTrades, "All trades should be ingested");
        assertEquals(numPairs, matchedTrades, "Each pair should match exactly once (no double-matching)");
        assertEquals(numPairs * 2, nettingSets, "2 netting sets per matched pair");
        assertEquals(numPairs * 2, settlementInstructions, "2 settlement instructions per matched pair");
        assertEquals(numPairs, committed, "Each matched pair should have 1 committed 2PC tx");
        assertEquals(0, aborted, "No 2PC transactions should abort");
        assertEquals(numPairs * 2, ((Number) ingestionQueue.get("DONE")).intValue(), "All ingestion messages should complete");
        assertEquals(numPairs * 2, ((Number) matchingQueue.get("DONE")).intValue(), "All matching messages should complete");
        assertEquals(numPairs, ((Number) nettingQueue.get("DONE")).intValue(), "One netting message per matched pair should complete");
        assertEquals(0, ((Number) ingestionQueue.get("FAILED")).intValue(), "No ingestion messages should fail");
        assertEquals(0, ((Number) matchingQueue.get("FAILED")).intValue(), "No matching messages should fail");
        assertEquals(0, ((Number) nettingQueue.get("FAILED")).intValue(), "No netting messages should fail");

        submitter.shutdown();
    }

    private String buildTradeXml(String msgId, String tradeId, String tradeType,
                                  String party1Id, String role1,
                                  String party2Id, String role2,
                                  String ccy1, String amt1,
                                  String ccy2, String amt2,
                                  String rate, String valueDate) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <tradeMessage>
                  <header>
                    <messageId>%s</messageId>
                    <creationTimestamp>2026-03-24T10:00:00Z</creationTimestamp>
                  </header>
                  <trade>
                    <tradeId>%s</tradeId>
                    <tradeType>%s</tradeType>
                    <party1>
                      <partyId>%s</partyId>
                      <role>%s</role>
                    </party1>
                    <party2>
                      <partyId>%s</partyId>
                      <role>%s</role>
                    </party2>
                    <currencyPair>
                      <currency1>%s</currency1>
                      <amount1>%s</amount1>
                      <currency2>%s</currency2>
                      <amount2>%s</amount2>
                      <exchangeRate>%s</exchangeRate>
                    </currencyPair>
                    <valueDate>%s</valueDate>
                  </trade>
                </tradeMessage>
                """.formatted(msgId, tradeId, tradeType,
                party1Id, role1, party2Id, role2,
                ccy1, amt1, ccy2, amt2, rate, valueDate);
    }
}
