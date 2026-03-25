package com.cit.clsnet.shared.failure;

public class QueueProcessingException extends RuntimeException {

    private final FailureContext failureContext;

    public QueueProcessingException(String message, boolean retryable) {
        this(message, FailureReason.PROCESSING_ERROR, retryable);
    }

    public QueueProcessingException(String message, Throwable cause, boolean retryable) {
        this(message, cause, FailureReason.PROCESSING_ERROR, retryable);
    }

    public QueueProcessingException(String message, FailureReason reason, boolean retryable) {
        super(message);
        this.failureContext = FailureContext.of(message, reason, retryable);
    }

    public QueueProcessingException(String message, Throwable cause, FailureReason reason, boolean retryable) {
        super(message, cause);
        this.failureContext = FailureContext.of(message, reason, retryable);
    }

    public QueueProcessingException(FailureContext failureContext) {
        super(failureContext.getMessage());
        this.failureContext = failureContext;
    }

    public QueueProcessingException(FailureContext failureContext, Throwable cause) {
        super(failureContext.getMessage(), cause);
        this.failureContext = failureContext;
    }

    public boolean isRetryable() {
        return failureContext.isRetryable();
    }

    public FailureContext getFailureContext() {
        return failureContext;
    }
}
