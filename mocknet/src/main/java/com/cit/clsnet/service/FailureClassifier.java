package com.cit.clsnet.service;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class FailureClassifier {

    public FailureContext classify(Throwable error) {
        return classify(error, FailureReason.PROCESSING_ERROR, null);
    }

    public FailureContext classify(Throwable error, FailureReason fallbackReason, String fallbackMessage) {
        if (error instanceof QueueProcessingException queueProcessingException) {
            return queueProcessingException.getFailureContext();
        }

        Throwable current = error;
        while (current != null) {
            if (current instanceof ObjectOptimisticLockingFailureException
                    || current instanceof OptimisticLockException
                    || current instanceof CannotAcquireLockException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof PessimisticLockingFailureException) {
                return FailureContext.of(messageOrDefault(error, fallbackMessage), FailureReason.CONCURRENCY_CONFLICT, true);
            }

            if (current instanceof TransientDataAccessException) {
                return FailureContext.of(messageOrDefault(error, fallbackMessage), FailureReason.TRANSIENT_DATA_ACCESS, true);
            }

            if (current instanceof DataIntegrityViolationException) {
                return FailureContext.of(messageOrDefault(error, fallbackMessage), FailureReason.DATA_INTEGRITY_VIOLATION, false);
            }

            current = current.getCause();
        }

        return FailureContext.of(messageOrDefault(error, fallbackMessage), fallbackReason, false);
    }

    private String messageOrDefault(Throwable error, String fallbackMessage) {
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        if (fallbackMessage != null && !fallbackMessage.isBlank()) {
            return fallbackMessage;
        }
        return "Processing failed";
    }
}
