package com.cit.mocknet.config;

import com.cit.mocknet.shared.failure.FailureReason;
import com.cit.mocknet.shared.failure.QueueProcessingException;
import com.cit.mocknet.shared.payload.TextPayloadCorrelationReader;
import io.opentelemetry.api.trace.Span;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


/** Deterministic fault injection for the opt-in local observability demo only. */
@Aspect
@Component
@Profile("observability-demo")
@Order(1)
public class ObservabilityDemoFaults {
    private final TextPayloadCorrelationReader reader = new TextPayloadCorrelationReader();

    @Around("execution(* com.cit.mocknet.ingestion.TradeIngestionService.processTradeXml(..))")
    public Object inject(ProceedingJoinPoint joinPoint) throws Throwable {
        String tradeId = reader.extract((String) joinPoint.getArgs()[0]).tradeId();
        if (tradeId != null && tradeId.startsWith("DEMO-SLOW-")) {
            Span.current().setAttribute("demo.scenario", "slow");
            Thread.sleep(300);
        }
        if (tradeId != null && tradeId.startsWith("DEMO-RETRY-")) {
            var message = com.cit.mocknet.observability.ProcessingContext.current();
            int attempt = message == null ? 1 : message.getAttempts()+1;
            Span.current().setAttribute("demo.scenario", "retry");
            if (tradeId.startsWith("DEMO-RETRY-EXHAUST-") || attempt <= 2) {
                throw new QueueProcessingException("Injected demo concurrency conflict",
                        FailureReason.CONCURRENCY_CONFLICT, true);
            }
        }
        return joinPoint.proceed();
    }
}
