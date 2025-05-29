package com.example.vrcmonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Utility to enforce rate limiting for VRChat API calls.
 * 
 * This ensures all API requests are properly throttled by:
 * 1. Ensuring at least 1 second between the start of subsequent requests
 * 2. Ensuring at least 0.5 seconds after the completion of one request before starting another
 * 
 * These limits apply across all users and request types.
 */
@Component
public class ApiRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimiter.class);
    
    // Minimum time between request starts (1 second)
    private static final long MIN_TIME_BETWEEN_STARTS_MS = 1000;
    
    // Minimum time after a request finishes before the next can start (0.5 seconds)
    private static final long MIN_TIME_AFTER_FINISH_MS = 500;
    
    // Last request start and finish times
    private volatile Instant lastRequestStartTime = Instant.EPOCH;
    private volatile Instant lastRequestFinishTime = Instant.EPOCH;
    
    /**
     * Waits as needed to satisfy API rate limiting constraints:
     * - At least 1 second since last request start
     * - At least 0.5 seconds since last request finish
     * 
     * After waiting if needed, updates the request start time.
     */
    public synchronized void waitForThrottlingConstraints() {
        Instant now = Instant.now();
        
        // Calculate times since last request start and finish
        long msSinceLastStart = now.toEpochMilli() - lastRequestStartTime.toEpochMilli();
        long msSinceLastFinish = now.toEpochMilli() - lastRequestFinishTime.toEpochMilli();
        
        // Calculate how long we need to wait for each constraint
        long msToWaitForStartConstraint = Math.max(0, MIN_TIME_BETWEEN_STARTS_MS - msSinceLastStart);
        long msToWaitForFinishConstraint = Math.max(0, MIN_TIME_AFTER_FINISH_MS - msSinceLastFinish);
        
        // Take the maximum wait time to satisfy both constraints
        long msToWait = Math.max(msToWaitForStartConstraint, msToWaitForFinishConstraint);
        
        if (msToWait > 0) {
            try {
                log.debug("Throttling API request: waiting {}ms ({}ms since last start, {}ms since last finish)",
                        msToWait, msSinceLastStart, msSinceLastFinish);
                Thread.sleep(msToWait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Throttling wait interrupted", e);
            }
        }
        
        // After potentially waiting, update the start time
        lastRequestStartTime = Instant.now();
    }
    
    /**
     * Records that a request has finished, updating the finish timestamp.
     * This should be called when an API request completes (successfully or with error).
     */
    public synchronized void recordRequestFinished() {
        lastRequestFinishTime = Instant.now();
        log.debug("Recorded API request completion at {}", lastRequestFinishTime);
    }
    
    /**
     * Execute a task with proper API rate limiting.
     * This method will:
     * 1. Wait if necessary to satisfy rate limiting constraints
     * 2. Execute the provided task
     * 3. Record the completion time
     * 
     * @param <T> The result type of the task
     * @param task The task to execute
     * @return The result of the task
     */
    public <T> T executeWithRateLimit(Supplier<T> task) {
        // Wait as needed before starting
        waitForThrottlingConstraints();
        
        try {
            // Execute the task
            return task.get();
        } finally {
            // Record when we finished, regardless of success or failure
            lastRequestFinishTime = Instant.now();
        }
    }
    
    /**
     * Execute a CompletableFuture task with proper API rate limiting.
     * This is for async operations where we need to enforce rate limiting.
     * 
     * @param <T> The result type of the task
     * @param asyncTask The async task to execute
     * @return A CompletableFuture with the result
     */
    public <T> CompletableFuture<T> executeWithRateLimitAsync(Supplier<CompletableFuture<T>> asyncTask) {
        // Wait as needed before starting
        waitForThrottlingConstraints();
        
        // Start the async task
        CompletableFuture<T> future = asyncTask.get();
        
        // When it completes (successfully or with exception), record the finish time
        return future.whenComplete((result, ex) -> {
            lastRequestFinishTime = Instant.now();
            if (ex != null) {
                log.debug("Rate-limited async task completed with exception", ex);
            }
        });
    }
} 