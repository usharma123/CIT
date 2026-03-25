package com.cit.clsnet.shared.failure;

public final class FailureContext {

    private final String message;
    private final FailureReason reason;
    private final boolean retryable;

    public FailureContext(String message, FailureReason reason, boolean retryable) {
        this.message = message == null || message.isBlank() ? "Processing failed" : message;
        this.reason = reason == null ? FailureReason.PROCESSING_ERROR : reason;
        this.retryable = retryable;
    }

    public static FailureContext of(String message, FailureReason reason, boolean retryable) {
        return new FailureContext(message, reason, retryable);
    }

    public String getMessage() {
        return message;
    }

    public FailureReason getReason() {
        return reason;
    }

    public String getReasonCode() {
        return reason.code();
    }

    public boolean isRetryable() {
        return retryable;
    }
}
