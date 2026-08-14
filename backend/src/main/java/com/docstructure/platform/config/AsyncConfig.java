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

    /**
     * Backs BulkReextractionService#reextractByDocTypeAsync — deliberately a separate pool from
     * extractionExecutor, not a shared one: that pool's job is real per-document extraction work
     * (can legitimately run long, one document at a time), while this one just finds documents
     * matching a doc type and enqueues each — an administrative dispatch step that should never
     * sit queued behind a burst of actual extraction work. Sharing the pool was tried first and
     * caused exactly that: under concurrent load, saving a rule set (which triggers this) could
     * be delayed well past what an admin would expect from an action that's supposed to return
     * immediately, confirmed live via test flakiness once both used the same pool.
     */
    @Bean("bulkReextractionExecutor")
    public Executor bulkReextractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bulk-reextract-");
        executor.setRejectedExecutionHandler((task, exec) -> {
            log.warn("bulk re-extraction executor saturated (active={}, queued={}) — running on caller thread",
                    exec.getActiveCount(), exec.getQueue().size());
            task.run();
        });
        executor.initialize();
        log.info("bulk re-extraction executor initialized core={} max={} queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 50);
        return executor;
    }
}
