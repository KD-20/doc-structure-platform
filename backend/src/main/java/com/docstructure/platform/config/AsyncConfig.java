package com.docstructure.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Backs the background extraction pipeline (see ExtractionWorker) — a dedicated, bounded pool
 * rather than Spring's default SimpleAsyncTaskExecutor, which spawns an unbounded thread per
 * task and would let a burst of uploads exhaust the JVM under load.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("extractionExecutor")
    public Executor extractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("extraction-");
        // Caller-runs, not the default AbortPolicy: this fires from a @TransactionalEventListener
        // callback thread (after the enqueueing transaction has already committed), not the
        // original HTTP thread — running it there when the pool is saturated is a safe degrade
        // to synchronous processing rather than silently dropping the extraction request.
        executor.setRejectedExecutionHandler((task, exec) -> {
            log.warn("extraction executor saturated (active={}, queued={}) — running on caller thread",
                    exec.getActiveCount(), exec.getQueue().size());
            task.run();
        });
        executor.initialize();
        log.info("extraction executor initialized core={} max={} queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 200);
        return executor;
    }
}
