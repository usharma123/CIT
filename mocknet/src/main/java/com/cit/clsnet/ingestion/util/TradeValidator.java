package com.cit.clsnet.ingestion.util;

import com.cit.clsnet.ingestion.CurrencyValidationService;
import com.cit.clsnet.ingestion.ParsedTradeMessage;
import com.cit.clsnet.ingestion.ValidationResult;
import com.cit.clsnet.shared.failure.FailureReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TradeValidator {

    private final CurrencyValidationService currencyValidationService;

    public TradeValidator(CurrencyValidationService currencyValidationService) {
        this.currencyValidationService = currencyValidationService;
    }

    public ValidationResult validate(ParsedTradeMessage trade) {
        if (trade.tradeId() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_TRADE_ID, "Trade missing tradeId");
        }
        if (trade.counterparty1() == null || trade.counterparty2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_PARTY, "Trade missing party information");
        }
        if (trade.currency1() == null || trade.currency2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_CURRENCY, "Trade missing currency information");
        }
        if (trade.amount1() == null || trade.amount2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_AMOUNT, "Trade missing amount information");
        }
        if (trade.valueDate() == null) {
            return ValidationResult.terminal(FailureReason.INVALID_VALUE_DATE, "Trade valueDate is invalid");
        }
        if (!currencyValidationService.isSupported(trade.currency1()) || !currencyValidationService.isSupported(trade.currency2())) {
            return ValidationResult.softReject(FailureReason.UNSUPPORTED_CURRENCY, "Trade uses an unsupported currency");
        }
        if (trade.amount1().compareTo(BigDecimal.ZERO) <= 0 || trade.amount2().compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationResult.softReject(FailureReason.INVALID_AMOUNT, "Trade amount must be positive");
        }
        return ValidationResult.success();
    }
}
