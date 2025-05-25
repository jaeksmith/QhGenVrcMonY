package com.example.vrcmonitor.services;

import com.example.vrcmonitor.config.AppConfig;
import com.example.vrcmonitor.config.ConfigLoader;
import com.example.vrcmonitor.config.UserConfig;
import com.example.vrcmonitor.web.StatusUpdateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.vrcmonitor.models.VRChatUser;

@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final long API_CALL_INTERVAL_MS = 1000; // 1 second between API calls

    private final ConfigLoader configLoader;
    private final VRChatApiService vrchatApiService;
    private final UserStateService userStateService;
    private final StatusUpdateHandler statusUpdateHandler;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, UserConfig> userConfigMap = new ConcurrentHashMap<>();

    // Queue for UIDs ready to be polled
    private final BlockingQueue<String> pollQueue = new LinkedBlockingQueue<>();
    // Single thread executor to process the queue and enforce delay
    private final ExecutorService queueProcessorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vrc-api-poll-processor");
        thread.setDaemon(true); // Allow JVM to exit if this is the only thread left
        return thread;
    });
    private final AtomicBoolean processorRunning = new AtomicBoolean(false);

    public MonitoringService(ConfigLoader configLoader, VRChatApiService vrchatApiService, UserStateService userStateService, StatusUpdateHandler statusUpdateHandler, TaskScheduler taskScheduler) {
        this.configLoader = configLoader;
        this.vrchatApiService = vrchatApiService;
        this.userStateService = userStateService;
        this.statusUpdateHandler = statusUpdateHandler;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void initialize() {
        log.info("MonitoringService initializing...");
        // Load config into map for faster lookup later
        AppConfig config = configLoader.getConfig();
        if (config != null && config.getUsers() != null) {
            config.getUsers().forEach(uc -> userConfigMap.put(uc.getVrcUid(), uc));
        }
         // Don't start processor thread here, wait for startMonitoring call
    }

    public void startMonitoring() {
        if (vrchatApiService.getAuthCookie() == null) {
            log.error("Cannot start monitoring: Not authenticated.");
            return;
        }
        if (userConfigMap.isEmpty()) {
             log.warn("No users configured to monitor.");
             return;
        }

        log.info("Starting monitoring tasks and queue processor...");
        // Start the queue processor thread if not already running
        if (processorRunning.compareAndSet(false, true)) {
             queueProcessorExecutor.submit(this::processPollQueue);
             log.info("API Poll Queue Processor started.");
        } else {
             log.warn("Queue processor already running.");
        }

        // Schedule tasks to add users to the queue at their poll rate
        userConfigMap.values().forEach(this::scheduleUserPollingTrigger);
        log.info("User polling triggers scheduled.");
    }

    // Renamed: schedules adding UID to queue
    private void scheduleUserPollingTrigger(UserConfig userConfig) {
        String vrcUid = userConfig.getVrcUid();
        Duration pollRate = userConfig.getPollRateDuration();
        String hrToken = userConfig.getHrToken();

        log.info("Scheduling trigger for {} ({}) every {}", hrToken, vrcUid, pollRate);

        // This runnable just adds the UID to the queue
        Runnable triggerTask = () -> {
            log.debug("Queueing poll for: {} ({})", hrToken, vrcUid);
            // Offer vs put: offer doesn't block if queue is full (less likely with single processor)
            if (!pollQueue.offer(vrcUid)) {
                 log.warn("Could not add {} to poll queue (queue might be full?). Skipping poll.", hrToken);
            }
        };

        ScheduledFuture<?> scheduledTask = taskScheduler.scheduleWithFixedDelay(triggerTask, pollRate);
        scheduledTasks.put(vrcUid, scheduledTask);
    }

    // The loop run by the single executor thread
    private void processPollQueue() {
        log.info("API Poll Queue Processor thread started.");
        long lastApiCallTime = 0;
        while (processorRunning.get()) {
            try {
                String vrcUid = pollQueue.take(); // Blocks until a UID is available
                UserConfig userConfig = userConfigMap.get(vrcUid); 
                if (userConfig == null) {
                     log.warn("User config not found for UID {} from queue, skipping.", vrcUid);
                     continue;
                }
                String hrToken = userConfig.getHrToken();
                
                // --- Enforce Delay ---
                long now = System.currentTimeMillis();
                long timeSinceLastCall = now - lastApiCallTime;
                if (timeSinceLastCall < API_CALL_INTERVAL_MS) {
                    long delayNeeded = API_CALL_INTERVAL_MS - timeSinceLastCall;
                    log.trace("Throttling API call for {}. Waiting {}ms.", hrToken, delayNeeded);
                    Thread.sleep(delayNeeded); 
                }
                // ---------------------

                log.debug("Processing poll for: {} ({})", hrToken, vrcUid);
                lastApiCallTime = System.currentTimeMillis(); // Record call time

                // Make the API call - BLOCKING on this dedicated thread is acceptable
                try {
                    VRChatUser user = vrchatApiService.getUserByUid(vrcUid).block(); // Block to wait for result

                    if (user != null) {
                        // Success
                        log.debug("[Processor] Received user object for {}: {}", hrToken, user.getStatus());
                        UserStateService.UserState newState = new UserStateService.UserState(user, UserStateService.StatusType.OK, null, Instant.now());
                        userStateService.updateUserState(vrcUid, user, Instant.now());
                        log.debug("[Processor] Called updateUserState for {}", hrToken);
                        statusUpdateHandler.broadcastStatusUpdate(newState);
                    } else {
                        // getUserByUid returned Mono.empty() - indicates an error was handled internally
                        log.warn("[Processor] Poll for {} completed empty (error handled in VRChatApiService). Updating state accordingly.", hrToken);
                        // Need to fetch the *reason* why it was empty if possible, maybe check UserStateService?
                        // For now, create a generic error state if the last wasn't already error.
                        UserStateService.UserState currentState = userStateService.getLatestUserState(vrcUid);
                        if (currentState == null || currentState.statusType() == UserStateService.StatusType.OK) {
                              String errorMessage = "API call failed or returned empty."; // More specific than before
                              UserStateService.UserState errorState = new UserStateService.UserState(null, UserStateService.StatusType.ERROR, errorMessage, Instant.now());
                              userStateService.updateUserErrorState(vrcUid, errorMessage, Instant.now());
                              statusUpdateHandler.broadcastStatusUpdate(errorState);
                        }
                    }
                } catch (Exception e) {
                     // Catch exceptions from .block() or other sync issues
                     log.error("[Processor] Exception during API call or processing for {}: {}", hrToken, e.getMessage(), e);
                     // Create and broadcast an error state
                     String errorMessage = "Processing error: " + e.getMessage();
                     UserStateService.UserState errorState = new UserStateService.UserState(null, UserStateService.StatusType.ERROR, errorMessage, Instant.now());
                     userStateService.updateUserErrorState(vrcUid, errorMessage, Instant.now());
                     statusUpdateHandler.broadcastStatusUpdate(errorState);
                }

            } catch (InterruptedException e) {
                log.info("Poll processor thread interrupted. Shutting down...");
                Thread.currentThread().interrupt(); // Re-interrupt thread
                processorRunning.set(false); // Ensure loop condition is false
            }
        }
        log.info("API Poll Queue Processor thread finished.");
    }

    @PreDestroy
    public void stopMonitoring() {
        log.info("Stopping monitoring triggers...");
        scheduledTasks.forEach((vrcUid, future) -> {
            if (future != null && !future.isCancelled()) {
                future.cancel(false); // false = don't interrupt if running, just prevent future runs
                log.info("Cancelled polling trigger for user: {}", vrcUid);
            }
        });
        scheduledTasks.clear();
        userConfigMap.clear();

        log.info("Shutting down API poll queue processor...");
        processorRunning.set(false); // Signal loop to stop
        queueProcessorExecutor.shutdown(); // Disable new tasks from being submitted
        try {
            // Interrupt the processor thread to potentially break it out of queue.take()
            queueProcessorExecutor.awaitTermination(1, TimeUnit.SECONDS); 
            // Force shutdown if not terminated
             if (!queueProcessorExecutor.isTerminated()) {
                 log.warn("Queue processor did not terminate gracefully, forcing shutdown.");
                queueProcessorExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            log.warn("Interrupted while waiting for queue processor shutdown.");
            queueProcessorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Note: Spring's TaskScheduler is managed by Spring, no need to shut it down here
        log.info("Monitoring stopped.");
    }
} 