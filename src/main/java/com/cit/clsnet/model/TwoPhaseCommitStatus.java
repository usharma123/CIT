package com.cit.clsnet.model;

public enum TwoPhaseCommitStatus {
    INITIATED,       // 2PC transaction started
    PREPARE_SENT,    // Prepare requests sent to participants
    PREPARED,        // All participants voted COMMIT
    COMMITTING,      // Commit phase in progress
    COMMITTED,       // All participants committed
    ABORTING,        // Abort phase in progress
    ABORTED          // Transaction aborted
}
