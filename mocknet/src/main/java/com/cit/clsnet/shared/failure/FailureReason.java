package com.cit.clsnet.shared.failure;

public enum FailureReason {
    INVALID_XML("invalid_xml"),
    MISSING_TRADE("missing_trade"),
    MISSING_TRADE_ID("missing_trade_id"),
    MISSING_PARTY("missing_party"),
    MISSING_CURRENCY("missing_currency"),
    MISSING_AMOUNT("missing_amount"),
    INVALID_AMOUNT("invalid_amount"),
    UNSUPPORTED_CURRENCY("unsupported_currency"),
    INVALID_VALUE_DATE("invalid_value_date"),
    INVALID_MATCHING_MESSAGE("invalid_matching_message"),
    MISSING_MATCHING_TRADE_ID("missing_matching_trade_id"),
    INVALID_NETTING_MESSAGE("invalid_netting_message"),
    INVALID_SETTLEMENT_MESSAGE("invalid_settlement_message"),
    TRADE_NOT_FOUND("trade_not_found"),
    CONCURRENCY_CONFLICT("concurrency_conflict"),
    TRANSIENT_DATA_ACCESS("transient_data_access"),
    DATA_INTEGRITY_VIOLATION("data_integrity_violation"),
    TWO_PHASE_COMMIT_ABORTED("two_phase_commit_aborted"),
    PROCESSING_ERROR("processing_error");

    private final String code;

    FailureReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
