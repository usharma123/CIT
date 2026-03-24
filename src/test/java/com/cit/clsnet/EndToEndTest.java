package com.cit.clsnet;

import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EndToEndTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String BUY_TRADE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tradeMessage>
              <header>
                <messageId>MSG-TEST-001</messageId>
                <creationTimestamp>2026-03-24T10:00:00Z</creationTimestamp>
              </header>
              <trade>
                <tradeId>TRD-TEST-001</tradeId>
                <tradeType>SPOT</tradeType>
                <party1>
                  <partyId>BANK_X</partyId>
                  <role>BUYER</role>
                </party1>
                <party2>
                  <partyId>BANK_Y</partyId>
                  <role>SELLER</role>
                </party2>
                <currencyPair>
                  <currency1>USD</currency1>
                  <amount1>500000.00</amount1>
                  <currency2>GBP</currency2>
                  <amount2>395000.00</amount2>
                  <exchangeRate>1.2658228</exchangeRate>
                </currencyPair>
                <valueDate>2026-04-01</valueDate>
              </trade>
            </tradeMessage>
            """;

    private static final String SELL_TRADE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tradeMessage>
              <header>
                <messageId>MSG-TEST-002</messageId>
                <creationTimestamp>2026-03-24T10:05:00Z</creationTimestamp>
              </header>
              <trade>
                <tradeId>TRD-TEST-002</tradeId>
                <tradeType>SPOT</tradeType>
                <party1>
                  <partyId>BANK_Y</partyId>
                  <role>SELLER</role>
                </party1>
                <party2>
                  <partyId>BANK_X</partyId>
                  <role>BUYER</role>
                </party2>
                <currencyPair>
                  <currency1>USD</currency1>
                  <amount1>500000.00</amount1>
                  <currency2>GBP</currency2>
                  <amount2>395000.00</amount2>
                  <exchangeRate>1.2658228</exchangeRate>
                </currencyPair>
                <valueDate>2026-04-01</valueDate>
              </trade>
            </tradeMessage>
            """;

    private ResponseEntity<Map> postTrade(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.exchange("/api/trades", HttpMethod.POST,
                new HttpEntity<>(xml, headers), Map.class);
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

    @Test
    void fullPipeline_submitTwoTrades_producesSettlementInstructions() throws Exception {
        // Submit buy-side trade
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        ResponseEntity<Map> buyResponse = restTemplate.exchange(
                "/api/trades",
                HttpMethod.POST,
                new HttpEntity<>(BUY_TRADE_XML, headers),
                Map.class);
        assertEquals(HttpStatus.ACCEPTED, buyResponse.getStatusCode());

        // Submit sell-side trade
        ResponseEntity<Map> sellResponse = restTemplate.exchange(
                "/api/trades",
                HttpMethod.POST,
                new HttpEntity<>(SELL_TRADE_XML, headers),
                Map.class);
        assertEquals(HttpStatus.ACCEPTED, sellResponse.getStatusCode());

        // Wait for async pipeline processing
        Thread.sleep(3000);

        // Verify trades were ingested
        ResponseEntity<List<Trade>> tradesResponse = restTemplate.exchange(
                "/api/trades",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, tradesResponse.getStatusCode());
        assertNotNull(tradesResponse.getBody());
        assertEquals(2, tradesResponse.getBody().size());

        // Verify settlement instructions were generated
        ResponseEntity<List<SettlementInstruction>> settlementsResponse = restTemplate.exchange(
                "/api/settlement-instructions",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, settlementsResponse.getStatusCode());
        assertNotNull(settlementsResponse.getBody());
        assertEquals(2, settlementsResponse.getBody().size());

        // Verify the settlement amounts
        List<SettlementInstruction> instructions = settlementsResponse.getBody();
        SettlementInstruction usdInstruction = instructions.stream()
                .filter(i -> "USD".equals(i.getCurrency()))
                .findFirst()
                .orElseThrow();
        SettlementInstruction gbpInstruction = instructions.stream()
                .filter(i -> "GBP".equals(i.getCurrency()))
                .findFirst()
                .orElseThrow();

        // USD: seller (BANK_Y) pays buyer (BANK_X) 500,000
        assertEquals("BANK_Y", usdInstruction.getPayerParty());
        assertEquals("BANK_X", usdInstruction.getReceiverParty());
        assertEquals(0, usdInstruction.getAmount().compareTo(new java.math.BigDecimal("500000.0000")));

        // GBP: buyer (BANK_X) pays seller (BANK_Y) 395,000
        assertEquals("BANK_X", gbpInstruction.getPayerParty());
        assertEquals("BANK_Y", gbpInstruction.getReceiverParty());
        assertEquals(0, gbpInstruction.getAmount().compareTo(new java.math.BigDecimal("395000.0000")));

        // Check pipeline status
        ResponseEntity<Map> statusResponse = restTemplate.getForEntity("/api/status", Map.class);
        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        Map<String, Object> statusBody = statusResponse.getBody();
        assertNotNull(statusBody);
        assertEquals(2, ((Number) statusBody.get("settlementInstructions")).intValue());

        // Verify 2-Phase Commit transaction log
        Map<String, Object> twopcCounts = (Map<String, Object>) statusBody.get("twoPhaseCommitTransactions");
        assertNotNull(twopcCounts, "2PC transaction counts should be present in status");
        assertEquals(1, ((Number) twopcCounts.get("COMMITTED")).intValue(),
                "Should have exactly 1 committed 2PC transaction");

        // Verify participant votes (2 participants: NettingCalculator + SettlementInstructor)
        assertEquals(2, ((Number) statusBody.get("participantVotes")).intValue(),
                "Should have 2 participant votes (NettingCalculator + SettlementInstructor)");

        // Verify transaction log details
        ResponseEntity<List<Map>> txLogResponse = restTemplate.exchange(
                "/api/transaction-log",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, txLogResponse.getStatusCode());
        List<Map> txLogs = txLogResponse.getBody();
        assertNotNull(txLogs);
        assertEquals(1, txLogs.size());
        assertEquals("COMMITTED", txLogs.get(0).get("status"));
        assertEquals("NETTING_SETTLEMENT", txLogs.get(0).get("transactionType"));

        // Verify participant vote details
        ResponseEntity<List<Map>> votesResponse = restTemplate.exchange(
                "/api/participant-votes",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, votesResponse.getStatusCode());
        List<Map> votes = votesResponse.getBody();
        assertNotNull(votes);
        assertEquals(2, votes.size());
        assertTrue(votes.stream().allMatch(v -> "VOTE_COMMIT".equals(v.get("vote"))),
                "All participants should have voted COMMIT");
    }

    @Test
    void multipleTradesAcrossDifferentPairsAndCurrencies() throws Exception {
        // Trade pair 1: GOLDMAN buys 2M USD/JPY from MORGAN
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M01", "TRD-M01", "SPOT",
                "GOLDMAN", "BUYER", "MORGAN", "SELLER",
                "USD", "2000000.00", "JPY", "300000000.00",
                "150.0", "2026-04-02")).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M02", "TRD-M02", "SPOT",
                "MORGAN", "SELLER", "GOLDMAN", "BUYER",
                "USD", "2000000.00", "JPY", "300000000.00",
                "150.0", "2026-04-02")).getStatusCode());

        // Trade pair 2: CITI buys 500K EUR/CHF from DEUTSCHE
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M03", "TRD-M03", "FORWARD",
                "CITI", "BUYER", "DEUTSCHE", "SELLER",
                "EUR", "500000.00", "CHF", "470000.00",
                "0.94", "2026-05-15")).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M04", "TRD-M04", "FORWARD",
                "DEUTSCHE", "SELLER", "CITI", "BUYER",
                "EUR", "500000.00", "CHF", "470000.00",
                "0.94", "2026-05-15")).getStatusCode());

        // Trade pair 3: HSBC buys 1M GBP/USD from BARCLAYS
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M05", "TRD-M05", "SPOT",
                "HSBC", "BUYER", "BARCLAYS", "SELLER",
                "GBP", "1000000.00", "USD", "1265000.00",
                "1.265", "2026-04-02")).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, postTrade(buildTradeXml(
                "MSG-M06", "TRD-M06", "SPOT",
                "BARCLAYS", "SELLER", "HSBC", "BUYER",
                "GBP", "1000000.00", "USD", "1265000.00",
                "1.265", "2026-04-02")).getStatusCode());

        // Wait for pipeline
        Thread.sleep(5000);

        // Verify: 6 trades ingested
        ResponseEntity<List<Trade>> tradesResp = restTemplate.exchange(
                "/api/trades", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(6, tradesResp.getBody().size());

        // Verify: 3 matched trade pairs
        ResponseEntity<List<Map>> matchedResp = restTemplate.exchange(
                "/api/matched-trades", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(3, matchedResp.getBody().size());

        // Verify: 6 netting sets (2 per matched pair, one per currency)
        ResponseEntity<List<Map>> nettingResp = restTemplate.exchange(
                "/api/netting-sets", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(6, nettingResp.getBody().size());

        // Verify: 6 settlement instructions
        ResponseEntity<List<SettlementInstruction>> settleResp = restTemplate.exchange(
                "/api/settlement-instructions", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(6, settleResp.getBody().size());

        // Verify: 3 committed 2PC transactions
        ResponseEntity<Map> statusResp = restTemplate.getForEntity("/api/status", Map.class);
        Map<String, Object> body = statusResp.getBody();
        Map<String, Object> twopc = (Map<String, Object>) body.get("twoPhaseCommitTransactions");
        assertEquals(3, ((Number) twopc.get("COMMITTED")).intValue());

        // Verify: 6 participant votes (2 per 2PC transaction)
        assertEquals(6, ((Number) body.get("participantVotes")).intValue());

        // Spot-check: GOLDMAN/MORGAN USD settlement
        List<SettlementInstruction> instructions = settleResp.getBody();
        SettlementInstruction usdJpy = instructions.stream()
                .filter(i -> "USD".equals(i.getCurrency()) && "MORGAN".equals(i.getPayerParty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected MORGAN to pay GOLDMAN USD"));
        assertEquals("GOLDMAN", usdJpy.getReceiverParty());
        assertEquals(0, usdJpy.getAmount().compareTo(new java.math.BigDecimal("2000000.0000")));

        // Spot-check: CITI/DEUTSCHE CHF settlement
        SettlementInstruction eurChf = instructions.stream()
                .filter(i -> "CHF".equals(i.getCurrency()) && "CITI".equals(i.getPayerParty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected CITI to pay DEUTSCHE CHF"));
        assertEquals("DEUTSCHE", eurChf.getReceiverParty());
        assertEquals(0, eurChf.getAmount().compareTo(new java.math.BigDecimal("470000.0000")));
    }
}
