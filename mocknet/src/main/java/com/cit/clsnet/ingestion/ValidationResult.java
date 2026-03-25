package com.cit.clsnet.ingestion;

import com.cit.clsnet.shared.failure.FailureReason;

public record ValidationResult(boolean valid, boolean persistRejected, FailureReason reason, String message) {

    public static ValidationResult success() {
        return new ValidationResult(true, false, null, null);
    }

    public static ValidationResult softReject(FailureReason reason, String message) {
        return new ValidationResult(false, true, reason, message);
    }

    public static ValidationResult terminal(FailureReason reason, String message) {
        return new ValidationResult(false, false, reason, message);
    }
}
