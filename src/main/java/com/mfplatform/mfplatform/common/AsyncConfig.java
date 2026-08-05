package com.mfplatform.mfplatform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig configures the thread pool used by all @Async methods.
 *
 * WHY NOT USE THE DEFAULT:
 * Spring's default async executor (SimpleAsyncTaskExecutor) creates a NEW
 * thread for every single @Async invocation and never reuses threads.
 * Under any load this is catastrophic — thousands of threads created and
 * immediately abandoned. A thread pool reuses a fixed set of threads.
 *
 * THREAD POOL SIZING:
 *   corePoolSize (4)  — threads always alive, even when idle.
 *                       Sized to handle typical notification bursts
 *                       (e.g. NAV import → multiple SIP allotments in parallel).
 *   maxPoolSize (10)  — maximum threads under peak load.
 *                       Set conservatively — each thread uses ~256KB stack.
 *   queueCapacity (100) — tasks queued before new threads are created above core.
 *                         If queue fills AND pool is at max → rejection.
 *   threadNamePrefix  — "mf-async-" prefix makes async threads identifiable
 *                       in thread dumps, logs, and monitoring tools.
 *                       e.g. "mf-async-1", "mf-async-2"
 *
 * AsyncUncaughtExceptionHandler:
 * @Async methods that return void cannot propagate exceptions to the caller
 * (there's no caller waiting — the thread is detached). Without a handler,
 * exceptions from async methods are silently swallowed.
 * Our handler logs them so at least we can see failures in logs/audit.log.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mf-async-");
        executor.initialize();
        return executor;
    }

    /**
     * Handles exceptions thrown by @Async void methods.
     * Without this, exceptions from async notification sending are silently lost.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Uncaught exception in @Async method {}.{}(): {}",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    ex.getMessage(), ex);
    }
}
