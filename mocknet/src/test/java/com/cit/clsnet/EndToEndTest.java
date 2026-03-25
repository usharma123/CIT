package com.cit.clsnet;

import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.QueueMessageStatus;
import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.queue.QueueBroker;
import com.cit.clsnet.repository.QueueMessageRepository;
import com.cit.clsnet.shared.failure.FailureContext;
import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueFailureDisposition;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTracingConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EndToEndTest {

    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private QueueBroker queueBroker;

    @Autowired
    private QueueMessageRepository queueMessageRepository;

    @Autowired
    private InMemorySpanExporter spanExporter;

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

    @BeforeEach
    void resetTracing() {
        spanExporter.reset();
    }

    @Test
    void fullPipeline_submitTwoTrades_persistsBrokerLifecycleAndSettlement() throws Exception {
        assertEquals(HttpStatus.ACCEPTED, postTrade(BUY_TRADE_XML).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, postTrade(SELL_TRADE_XML).getStatusCode());

        awaitCondition("happy-path broker completion", STATE_TIMEOUT, () ->
                queueCount("INGESTION", "DONE") == 2
                        && queueCount("MATCHING", "DONE") == 2
                        && queueCount("NETTING", "DONE") == 1
                        && settlementInstructions().size() == 2);

        assertQueueTerminalCounts("INGESTION", 2, 0);
        assertQueueTerminalCounts("MATCHING", 2, 0);
        assertQueueTerminalCounts("NETTING", 1, 0);
        assertQueueTerminalCounts("SETTLEMENT", 0, 0);
        assertQueueTerminalCounts("DEAD_LETTER", 0, 0);

        List<Trade> trades = allTrades();
        assertEquals(2, trades.size());

        List<SettlementInstruction> instructions = settlementInstructions();
        assertEquals(2, instructions.size());

        SettlementInstruction usdInstruction = instructions.stream()
                .filter(i -> "USD".equals(i.getCurrency()))
                .findFirst()
                .orElseThrow();
        SettlementInstruction gbpInstruction = instructions.stream()
                .filter(i -> "GBP".equals(i.getCurrency()))
                .findFirst()
                .orElseThrow();

        assertEquals("BANK_Y", usdInstruction.getPayerParty());
        assertEquals("BANK_X", usdInstruction.getReceiverParty());
        assertEquals(0, usdInstruction.getAmount().compareTo(new BigDecimal("500000.0000")));

        assertEquals("BANK_X", gbpInstruction.getPayerParty());
        assertEquals("BANK_Y", gbpInstruction.getReceiverParty());
        assertEquals(0, gbpInstruction.getAmount().compareTo(new BigDecimal("395000.0000")));

        Map<String, Object> status = pipelineStatus();
        Map<String, Object> twopcCounts = castMap(status.get("twoPhaseCommitTransactions"));
        assertEquals(1, ((Number) twopcCounts.get("COMMITTED")).intValue());
        assertEquals(2, ((Number) status.get("participantVotes")).intValue());

        List<QueueMessage> ingestionMessages = queueMessages("INGESTION", "DONE", 10);
        List<QueueMessage> matchingMessages = queueMessages("MATCHING", "DONE", 10);
        List<QueueMessage> nettingMessages = queueMessages("NETTING", "DONE", 10);
        assertEquals(2, ingestionMessages.size());
        assertEquals(2, matchingMessages.size());
        assertEquals(1, nettingMessages.size());

        awaitCondition("component tracing to flush", STATE_TIMEOUT, this::hasExpectedComponentSpans);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        Set<String> spanNames = spans.stream().map(SpanData::getName).collect(Collectors.toSet());
        assertTrue(spanNames.contains("TradeSubmissionController.submitTrade"));
        assertTrue(spanNames.contains("TradeIngestionService.processTradeXml"));
        assertTrue(spanNames.contains("TradeMatchingEngine.processMatchingMessage"));
        assertTrue(spanNames.contains("NettingCalculator.processNettingMessage"));
        assertTrue(spanNames.contains("TwoPhaseCommitCoordinator.executeTransaction"));

        assertSpanCountAtLeast("TradeSubmissionController.submitTrade", 2);
        assertSpanCountAtLeast("TradeIngestionService.processTradeXml", 2);
        assertSpanCountAtLeast("TradeMatchingEngine.processMatchingMessage", 1);
        assertSpanCountAtLeast("NettingCalculator.processNettingMessage", 1);
        assertSpanCountAtLeast("TwoPhaseCommitCoordinator.executeTransaction", 1);

        assertHasStageTaggedSpan("TradeSubmissionController.submitTrade", "HTTP", "controller");
        assertHasStageTaggedSpan("TradeIngestionService.processTradeXml", "INGESTION", "service");
        assertHasStageTaggedSpan("TradeMatchingEngine.processMatchingMessage", "MATCHING", "service");
        assertHasStageTaggedSpan("NettingCalculator.processNettingMessage", "NETTING", "service");
        assertHasStageTaggedSpan("TwoPhaseCommitCoordinator.executeTransaction", "SETTLEMENT", "service");

        assertHasCorrelatedSpan("TradeSubmissionController.submitTrade", "TRD-TEST-001", "MSG-TEST-001");
        assertHasCorrelatedSpan("TradeSubmissionController.submitTrade", "TRD-TEST-002", "MSG-TEST-002");
        assertHasCorrelatedSpan("TradeIngestionService.processTradeXml", "TRD-TEST-001", "MSG-TEST-001");
        assertHasCorrelatedSpan("TradeIngestionService.processTradeXml", "TRD-TEST-002", "MSG-TEST-002");

        assertTrue(spans.stream().anyMatch(span ->
                "TradeIngestionService.processTradeXml".equals(span.getName())
                        && "INGESTION".equals(span.getAttributes().get(AttributeKey.stringKey("cls.stage")))
                        && "TRD-TEST-001".equals(span.getAttributes().get(AttributeKey.stringKey("trade.id")))
                        && "MSG-TEST-001".equals(span.getAttributes().get(AttributeKey.stringKey("message.id")))));

        assertTrue(spans.stream().anyMatch(span ->
                "repository".equals(span.getAttributes().get(AttributeKey.stringKey("component.kind")))
                        && "DATABASE".equals(span.getAttributes().get(AttributeKey.stringKey("cls.stage")))));
    }

    @Test
    void invalidTrade_isRejectedWithoutCreatingMatchingMessage() throws Exception {
        String invalidTradeXml = buildTradeXml(
                "MSG-BAD-001", "TRD-BAD-001", "SPOT",
                "BANK_A", "BUYER", "BANK_B", "SELLER",
                "USD", "100000.00", "XXX", "80000.00",
                "0.80", "2026-04-01");

        assertEquals(HttpStatus.ACCEPTED, postTrade(invalidTradeXml).getStatusCode());

        awaitCondition("invalid trade to finish ingestion", STATE_TIMEOUT, () -> queueCount("INGESTION", "DONE") == 1);

        assertQueueTerminalCounts("INGESTION", 1, 0);
        assertQueueTerminalCounts("MATCHING", 0, 0);
        assertQueueTerminalCounts("DEAD_LETTER", 0, 0);
        assertTrue(queueMessages("MATCHING", null, 10).isEmpty());

        Trade rejectedTrade = allTrades().stream()
                .filter(trade -> "TRD-BAD-001".equals(trade.getTradeId()))
                .findFirst()
                .orElseThrow();
        assertEquals("REJECTED", rejectedTrade.getStatus().name());

        awaitCondition("rejection spans to flush", STATE_TIMEOUT, () -> hasSpan("QueueMessage.process"));
        assertHasAttribute("TradeIngestionService.processTradeXml", "trade.outcome", "rejected");
        assertHasAttribute("TradeIngestionService.processTradeXml", "rejection.reason", "unsupported_currency");
        assertHasAttribute("QueueMessage.process", "processing.outcome", "rejected");
    }

    @Test
    void blankTradeId_failsWithoutPersistingTradeAndPublishesToDeadLetter() throws Exception {
        String invalidTradeXml = buildTradeXml(
                "MSG-BAD-002", "", "SPOT",
                "BANK_A", "BUYER", "BANK_B", "SELLER",
                "USD", "100000.00", "EUR", "80000.00",
                "0.80", "2026-04-01");

        assertEquals(HttpStatus.ACCEPTED, postTrade(invalidTradeXml).getStatusCode());

        awaitCondition("blank tradeId to fail", STATE_TIMEOUT, () ->
                queueCount("INGESTION", "FAILED") == 1 && queueCount("DEAD_LETTER", "NEW") == 1);

        assertEquals(0, allTrades().size());
        assertQueueTerminalCounts("MATCHING", 0, 0);

        QueueMessage deadLetterMessage = queueMessages("DEAD_LETTER", null, 10).get(0);
        assertTrue(deadLetterMessage.getPayload().contains("\"reasonCode\":\"missing_trade_id\""));
        assertTrue(deadLetterMessage.getPayload().contains("\"originalQueue\":\"INGESTION\""));

        awaitCondition("failure spans to flush", STATE_TIMEOUT, () -> hasSpan("QueueBroker.fail"));
        assertHasAttribute("QueueMessage.process", "processing.outcome", "failed");
        assertHasAttribute("QueueMessage.process", "failure.reason_code", "missing_trade_id");
        assertSameTraceForMessage("MSG-BAD-002",
                "QueueMessage.process",
                "TradeIngestionService.processTradeXml",
                "QueueBroker.fail");
    }

    @Test
    void malformedXml_failsAndPublishesToDeadLetter() throws Exception {
        String malformedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <tradeMessage>
                  <header><messageId>MSG-BAD-XML</messageId></header>
                  <trade><tradeId>TRD-BAD-XML</tradeId><UNCLOSED_TAG>
                """;

        assertEquals(HttpStatus.ACCEPTED, postTrade(malformedXml).getStatusCode());

        awaitCondition("malformed xml to fail", STATE_TIMEOUT, () ->
                queueCount("INGESTION", "FAILED") == 1 && queueCount("DEAD_LETTER", "NEW") == 1);

        assertEquals(0, allTrades().size());

        QueueMessage deadLetterMessage = queueMessages("DEAD_LETTER", null, 10).get(0);
        assertTrue(deadLetterMessage.getPayload().contains("\"reasonCode\":\"invalid_xml\""));

        awaitCondition("malformed spans to flush", STATE_TIMEOUT, () -> hasSpan("QueueMessage.process"));
        assertHasAttribute("QueueMessage.process", "failure.reason_code", "invalid_xml");
        assertHasErroredSpan("TradeIngestionService.processTradeXml");
    }

    @Test
    void unmatchedTrade_createsAndCompletesMatchingMessageButNoNettingMessage() throws Exception {
        String unmatchedTradeXml = buildTradeXml(
                "MSG-UNM-001", "TRD-UNM-001", "SPOT",
                "BANK_C", "BUYER", "BANK_D", "SELLER",
                "EUR", "250000.00", "CHF", "235000.00",
                "0.94", "2026-04-05");

        assertEquals(HttpStatus.ACCEPTED, postTrade(unmatchedTradeXml).getStatusCode());

        awaitCondition("unmatched trade to finish matching", STATE_TIMEOUT, () ->
                queueCount("INGESTION", "DONE") == 1
                        && queueCount("MATCHING", "DONE") == 1);

        assertQueueTerminalCounts("INGESTION", 1, 0);
        assertQueueTerminalCounts("MATCHING", 1, 0);
        assertQueueTerminalCounts("NETTING", 0, 0);
        assertQueueTerminalCounts("DEAD_LETTER", 0, 0);
        assertTrue(queueMessages("NETTING", null, 10).isEmpty());

        Trade unmatchedTrade = allTrades().stream()
                .filter(trade -> "TRD-UNM-001".equals(trade.getTradeId()))
                .findFirst()
                .orElseThrow();
        assertEquals("VALIDATED", unmatchedTrade.getStatus().name());
    }

    @Test
    void retryableMatchingFailure_retriesThenEndsFailedAfterMaxAttempts() throws Exception {
        QueueMessage message = queueBroker.publish(QueueName.MATCHING, "{\"tradeId\":999999}");
        message = markClaimed(message, 0);

        assertEquals(QueueFailureDisposition.RETRIED, queueBroker.fail(
                message,
                FailureContext.of("Retry matching due to concurrency conflict", FailureReason.CONCURRENCY_CONFLICT, true)));

        message = markClaimed(message, 1);
        assertEquals(QueueFailureDisposition.RETRIED, queueBroker.fail(
                message,
                FailureContext.of("Retry matching due to concurrency conflict", FailureReason.CONCURRENCY_CONFLICT, true)));

        message = markClaimed(message, 2);
        assertEquals(QueueFailureDisposition.FAILED, queueBroker.fail(
                message,
                FailureContext.of("Retry matching due to concurrency conflict", FailureReason.CONCURRENCY_CONFLICT, true)));

        List<QueueMessage> failedMessages = queueMessages("MATCHING", "FAILED", 10);
        assertEquals(1, failedMessages.size());
        assertEquals(3, failedMessages.get(0).getAttempts());
        assertTrue(failedMessages.get(0).getLastError().contains("Retry matching"));
        assertTrue(queueMessages("DEAD_LETTER", null, 10).get(0).getPayload().contains("\"reasonCode\":\"concurrency_conflict\""));
        assertTrue(queueMessages("NETTING", null, 10).isEmpty());
    }

    private void assertQueueTerminalCounts(String queueName, long done, long failed) {
        assertEquals(done, queueCount(queueName, "DONE"));
        assertEquals(failed, queueCount(queueName, "FAILED"));
        assertEquals(0, queueCount(queueName, "NEW"));
        assertEquals(0, queueCount(queueName, "PROCESSING"));
    }

    private boolean hasExpectedComponentSpans() {
        Set<String> spanNames = spanExporter.getFinishedSpanItems().stream()
                .map(SpanData::getName)
                .collect(Collectors.toSet());
        return spanNames.contains("TradeSubmissionController.submitTrade")
                && spanNames.contains("TradeIngestionService.processTradeXml")
                && spanNames.contains("TradeMatchingEngine.processMatchingMessage")
                && spanNames.contains("NettingCalculator.processNettingMessage")
                && spanNames.contains("TwoPhaseCommitCoordinator.executeTransaction");
    }

    private boolean hasSpan(String spanName) {
        return spanExporter.getFinishedSpanItems().stream()
                .anyMatch(span -> spanName.equals(span.getName()));
    }

    private void assertSpanCountAtLeast(String spanName, long expectedMinimum) {
        long actual = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> spanName.equals(span.getName()))
                .count();
        assertTrue(actual >= expectedMinimum,
                () -> "Expected at least " + expectedMinimum + " spans named " + spanName + " but found " + actual);
    }

    private void assertHasStageTaggedSpan(String spanName, String expectedStage, String expectedComponentKind) {
        assertTrue(spanExporter.getFinishedSpanItems().stream().anyMatch(span ->
                        spanName.equals(span.getName())
                                && expectedStage.equals(span.getAttributes().get(AttributeKey.stringKey("cls.stage")))
                                && expectedComponentKind.equals(span.getAttributes().get(AttributeKey.stringKey("component.kind")))),
                () -> "Expected a span named " + spanName + " tagged with cls.stage=" + expectedStage
                        + " and component.kind=" + expectedComponentKind);
    }

    private void assertHasCorrelatedSpan(String spanName, String tradeId, String messageId) {
        assertTrue(spanExporter.getFinishedSpanItems().stream().anyMatch(span ->
                        spanName.equals(span.getName())
                                && tradeId.equals(span.getAttributes().get(AttributeKey.stringKey("trade.id")))
                                && messageId.equals(span.getAttributes().get(AttributeKey.stringKey("message.id")))),
                () -> "Expected a correlated span named " + spanName + " for trade.id=" + tradeId
                        + " and message.id=" + messageId);
    }

    private void assertHasAttribute(String spanName, String attributeName, String expectedValue) {
        AttributeKey<String> key = AttributeKey.stringKey(attributeName);
        assertTrue(spanExporter.getFinishedSpanItems().stream().anyMatch(span ->
                        spanName.equals(span.getName())
                                && expectedValue.equals(span.getAttributes().get(key))),
                () -> "Expected span " + spanName + " to have " + attributeName + "=" + expectedValue);
    }

    private void assertHasErroredSpan(String spanName) {
        assertTrue(spanExporter.getFinishedSpanItems().stream().anyMatch(span ->
                        spanName.equals(span.getName())
                                && "ERROR".equals(span.getStatus().getStatusCode().name())),
                () -> "Expected an errored span named " + spanName);
    }

    private void assertSameTraceForMessage(String messageId, String... spanNames) {
        List<SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> messageId.equals(span.getAttributes().get(AttributeKey.stringKey("message.id"))))
                .filter(span -> List.of(spanNames).contains(span.getName()))
                .toList();
        assertEquals(spanNames.length, spans.size());
        String traceId = spans.get(0).getTraceId();
        assertTrue(spans.stream().allMatch(span -> traceId.equals(span.getTraceId())),
                () -> "Expected spans for message " + messageId + " to share a trace");
    }

    private ResponseEntity<Map> postTrade(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.exchange("/api/trades", HttpMethod.POST,
                new HttpEntity<>(xml, headers), Map.class);
    }

    private long queueCount(String queueName, String state) {
        Map<String, Map<String, Long>> queues = queueStatus();
        return queues.get(queueName).get(state);
    }

    private Map<String, Map<String, Long>> queueStatus() {
        ResponseEntity<Map<String, Map<String, Long>>> response = restTemplate.exchange(
                "/api/queues",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private List<QueueMessage> queueMessages(String queueName, String status, int limit) {
        String url = status == null
                ? "/api/queues/" + queueName + "/messages?limit=" + limit
                : "/api/queues/" + queueName + "/messages?status=" + status + "&limit=" + limit;
        ResponseEntity<List<QueueMessage>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private List<Trade> allTrades() {
        ResponseEntity<List<Trade>> response = restTemplate.exchange(
                "/api/trades",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private List<SettlementInstruction> settlementInstructions() {
        ResponseEntity<List<SettlementInstruction>> response = restTemplate.exchange(
                "/api/settlement-instructions",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private Map<String, Object> pipelineStatus() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/status",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private void awaitCondition(String description, Duration timeout, BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private QueueMessage markClaimed(QueueMessage message, int attempts) {
        QueueMessage claimed = queueMessageRepository.findById(message.getId()).orElseThrow();
        claimed.setStatus(QueueMessageStatus.PROCESSING);
        claimed.setAttempts(attempts);
        claimed.setWorkerName("retry-worker");
        claimed.setClaimedAt(Instant.now());
        claimed.setCompletedAt(null);
        return queueMessageRepository.save(claimed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
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
