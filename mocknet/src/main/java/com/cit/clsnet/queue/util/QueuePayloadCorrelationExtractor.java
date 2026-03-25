package com.cit.clsnet.queue.util;

import com.cit.clsnet.shared.payload.TextPayloadCorrelation;
import com.cit.clsnet.shared.payload.TextPayloadCorrelationReader;
import org.springframework.stereotype.Component;

@Component
public class QueuePayloadCorrelationExtractor {

    private final TextPayloadCorrelationReader correlationReader;

    public QueuePayloadCorrelationExtractor() {
        this.correlationReader = new TextPayloadCorrelationReader();
    }

    public QueuePayloadCorrelation extract(String payload) {
        TextPayloadCorrelation correlation = correlationReader.extract(payload);
        return new QueuePayloadCorrelation(
                correlation.tradeId(),
                correlation.messageId(),
                correlation.matchedTradeId(),
                correlation.nettingSetId());
    }
}
