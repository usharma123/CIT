package com.cit.clsnet.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedTradeMessage(
        String rawJson,
        String messageId,
        String tradeId,
        String tradeType,
        String counterparty1,
        String counterparty2,
        String role1,
        String role2,
        String currency1,
        BigDecimal amount1,
        String currency2,
        BigDecimal amount2,
        BigDecimal exchangeRate,
        LocalDate valueDate) {
}
