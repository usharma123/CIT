package com.cit.mocknet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class QueueConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ingestionExecutor(MocknetProperties props) {
        return newNamedPool(props.getThreads().getIngestion(), "ingestion-worker");
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService matchingExecutor(MocknetProperties props) {
        return newNamedPool(props.getThreads().getMatching(), "matching-worker");
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService nettingExecutor(MocknetProperties props) {
        return newNamedPool(props.getThreads().getNetting(), "netting-worker");
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService settlementExecutor(MocknetProperties props) {
        return newNamedPool(props.getThreads().getSettlement(), "settlement-worker");
    }

    private ExecutorService newNamedPool(int size, String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }
}
