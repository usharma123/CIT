package com.cit.clsnet.service;

public class QueueProcessingException extends RuntimeException {

    private final boolean retryable;

    public QueueProcessingException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public QueueProcessingException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
