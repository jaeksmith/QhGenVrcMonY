package com.example.vrcmonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    private static final Duration MIN_TIME_BETWEEN_REQUESTS = Duration.ofSeconds(1);
    
    // Minimum time after a request finishes before the next can start (0.5 seconds)
    private static final Duration MIN_TIME_AFTER_COMPLETION = Duration.ofMillis(500);
    
    // Last request start and finish times
    private volatile Instant lastRequestStartTime = Instant.EPOCH;
    private volatile Instant lastRequestFinishTime = Instant.EPOCH;
    
    // Add lock for thread safety
    private final Object limiterLock = new Object();
    
    /**
     * Wait for throttling constraints to be satisfied before proceeding with a request.
     * This ensures we're respecting both the time-since-last-start and time-since-last-finish rules.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void waitForThrottlingConstraints() throws InterruptedException {
        synchronized (limiterLock) {
            Instant now = Instant.now();
            
            // Check if we need to wait for the time-since-last-start rule
            if (lastRequestStartTime != null) {
                Duration timeSinceLastStart = Duration.between(lastRequestStartTime, now);
                if (timeSinceLastStart.compareTo(MIN_TIME_BETWEEN_REQUESTS) < 0) {
                    // Need to wait
                    long waitTime = MIN_TIME_BETWEEN_REQUESTS.toMillis() - timeSinceLastStart.toMillis();
                    log.debug("Rate limiting: Waiting {}ms to satisfy time-since-last-start constraint", waitTime);
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Rate limiting wait interrupted", e);
                        throw e;
                    }
                }
            }
            
            // Check if we need to wait for the time-since-last-finish rule
            if (lastRequestFinishTime != null) {
                Instant updatedNow = Instant.now(); // Refresh current time
                Duration timeSinceLastFinish = Duration.between(lastRequestFinishTime, updatedNow);
                if (timeSinceLastFinish.compareTo(MIN_TIME_AFTER_COMPLETION) < 0) {
                    // Need to wait
                    long waitTime = MIN_TIME_AFTER_COMPLETION.toMillis() - timeSinceLastFinish.toMillis();
                    log.debug("Rate limiting: Waiting {}ms to satisfy time-since-last-finish constraint", waitTime);
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Rate limiting wait interrupted", e);
                        throw e;
                    }
                }
            }
            
            // Update the start time
            lastRequestStartTime = Instant.now();
            log.debug("Rate limiting: Request started at {}", lastRequestStartTime);
        }
    }
    
    /**
     * Records that a request has finished, updating the finish timestamp.
     * This should be called when an API request completes (successfully or with error).
     */
    public void recordRequestFinished() {
        synchronized (limiterLock) {
            lastRequestFinishTime = Instant.now();
            log.debug("Recorded API request completion at {}", lastRequestFinishTime);
        }
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
        try {
            waitForThrottlingConstraints();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Throttling wait interrupted", e);
        }
        
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
        try {
            waitForThrottlingConstraints();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Throttling wait interrupted", e);
        }
        
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