package com.cit.mocknet.observability;

import com.cit.mocknet.model.QueueMessage;

/** Worker-local business correlation, restored explicitly at every queue boundary. */
public final class ProcessingContext {
    private static final ThreadLocal<QueueMessage> CURRENT = new ThreadLocal<>();
    private ProcessingContext() {}
    public static QueueMessage current() { return CURRENT.get(); }
    public static void set(QueueMessage message) { CURRENT.set(message); }
    public static void clear() { CURRENT.remove(); }
    public static String operationId() { return current() == null ? null : current().getOperationId(); }
}
