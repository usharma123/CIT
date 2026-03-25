package com.cit.clsnet.queue.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueuePayloadCorrelationExtractorTest {

    @Test
    void extract_readsXmlCorrelation() {
        QueuePayloadCorrelationExtractor extractor = new QueuePayloadCorrelationExtractor();

        QueuePayloadCorrelation correlation = extractor.extract("""
                <tradeMessage>
                  <header><messageId>MSG-Q-1</messageId></header>
                  <trade><tradeId>TRD-Q-1</tradeId></trade>
                </tradeMessage>
                """);

        assertEquals("MSG-Q-1", correlation.messageId());
        assertEquals("TRD-Q-1", correlation.tradeId());
    }

    @Test
    void extract_readsJsonCorrelation() {
        QueuePayloadCorrelationExtractor extractor = new QueuePayloadCorrelationExtractor();

        QueuePayloadCorrelation correlation = extractor.extract("{\"matchedTradeId\":99,\"messageId\":\"MSG-Q-2\",\"nettingSetId\":5}");

        assertEquals("99", correlation.matchedTradeId());
        assertEquals("MSG-Q-2", correlation.messageId());
        assertEquals("5", correlation.nettingSetId());
    }
}
