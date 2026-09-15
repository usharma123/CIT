package com.cit.mocknet.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class TracingConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry openTelemetry(@Value("${mocknet.tracing.enabled:true}") boolean enabled) {
        return enabled ? GlobalOpenTelemetry.get() : OpenTelemetry.noop();
    }
}
