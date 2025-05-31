package com.example.vrcmonitor.services;

import com.example.vrcmonitor.config.AppConfig;
import com.example.vrcmonitor.config.ConfigLoader;
import com.example.vrcmonitor.config.UserConfig;
import com.example.vrcmonitor.models.VRChatUser;
import com.example.vrcmonitor.web.StatusUpdateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    
    private final ConfigLoader configLoader;
    private final VRChatApiService vrchatApiService;
    private final UserStateService userStateService;
    private final StatusUpdateHandler statusUpdateHandler;
    private final AuthService authService;
    
    // For storing user config for quick lookup
    private final Map<String, UserConfig> userConfigMap = new ConcurrentHashMap<>();
    
    // Task scheduling
    private ThreadPoolTaskScheduler threadPoolTaskScheduler;
    private TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private boolean isRunning = false;

    public MonitoringService(ConfigLoader configLoader, VRChatApiService vrchatApiService, 
                            UserStateService userStateService, StatusUpdateHandler statusUpdateHandler,
                            AuthService authService) {
        this.configLoader = configLoader;
        this.vrchatApiService = vrchatApiService;
        this.userStateService = userStateService;
        this.statusUpdateHandler = statusUpdateHandler;
        this.authService = authService;
    }

    @PostConstruct
    public void init() {
        log.info("MonitoringService initializing...");
        // Don't automatically start polling, wait for active session
        
        // Initialize the scheduler
        threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setPoolSize(1);
        threadPoolTaskScheduler.setThreadNamePrefix("vrc-monitor-");
        threadPoolTaskScheduler.initialize();
        taskScheduler = threadPoolTaskScheduler;
    }

    public void startMonitoring() {
        if (!authService.hasActiveSession()) {
            log.error("Cannot start monitoring: Not authenticated.");
            return;
        }

        log.info("Starting user monitoring...");
        
        // Cancel any existing tasks
        stopMonitoring();
        
        // Get our configuration
        AppConfig config = configLoader.getConfig();
        
        if (config == null || config.getUsers() == null || config.getUsers().isEmpty()) {
            log.warn("No users to monitor. Check configuration.");
            return;
        }
        
        // IMPORTANT: API request throttling is now handled by ApiRateLimiter in VRChatApiService.
        // This ensures:
        // 1. At least 1 second between the start of any two VRChat API requests
        // 2. At least 0.5 seconds after a request completes before starting the next
        // 3. These limits apply across all users to prevent API rate limiting
        
        // Start monitoring for each user
        for (UserConfig user : config.getUsers()) {
            // Cache user config for later lookups
            userConfigMap.put(user.getVrcUid(), user);
            
            log.info("Scheduling monitoring for user: {} ({}) with poll rate: {}", 
                    user.getHrToken(), user.getVrcUid(), user.getPollRate());
            
            // Schedule a fixed-delay task for this user (fixed-delay means the next execution
            // waits until the previous completes, which helps avoid API rate limits)
            ScheduledFuture<?> task = taskScheduler.scheduleWithFixedDelay(
                () -> pollUserStatus(user),
                new Date(), // Start immediately - ApiRateLimiter will handle throttling
                user.getPollRateDuration().toMillis()
            );
            scheduledTasks.put(user.getVrcUid(), task);
        }
        
        isRunning = true;
        log.info("Monitoring started for {} users - API throttling ensures proper rate limiting", 
                 config.getUsers().size());
    }
    
    @PreDestroy
    public void stopMonitoring() {
        if (!isRunning) {
            log.info("Monitoring service already stopped.");
            return;
        }
        
        log.info("Stopping monitoring tasks...");
        
        // Cancel all scheduled tasks
        scheduledTasks.forEach((uid, future) -> {
            log.debug("Cancelling scheduled task for UID: {}", uid);
            future.cancel(false);
        });
        scheduledTasks.clear();
        
        isRunning = false;
        log.info("Monitoring stopped.");
    }

    // Method to poll a user's status
    private void pollUserStatus(UserConfig user) {
        if (!authService.hasActiveSession()) {
            log.warn("Skipping poll for {} - no active session", user.getHrToken());
            return;
        }
        
        log.debug("Polling status for user: {}", user.getHrToken());
        
        if (user.getVrcUid() == null || user.getVrcUid().isBlank()) {
            log.error("Cannot poll user with empty VRChat UID: {}", user.getHrToken());
            return;
        }
        
        try {
            // Make the API call using reactive approach
            vrchatApiService.getUserByUid(user.getVrcUid())
                .doOnNext(vrchatUser -> {
                    log.debug("Received user data for {}: {}", user.getHrToken(), vrchatUser.getStatus());
                    userStateService.updateUserState(user.getVrcUid(), vrchatUser, Instant.now());
                    broadcastUserUpdate(user.getVrcUid(), vrchatUser);
                })
                .doOnError(error -> {
                    log.error("Error polling user {}: {}", user.getHrToken(), error.getMessage());
                    userStateService.updateUserErrorState(user.getVrcUid(), error.getMessage(), Instant.now());
                    broadcastUserErrorUpdate(user.getVrcUid(), error.getMessage());
                })
                .subscribe();
        } catch (Exception e) {
            log.error("Exception during poll for {}: {}", user.getHrToken(), e.getMessage(), e);
            userStateService.updateUserErrorState(user.getVrcUid(), "Exception: " + e.getMessage(), Instant.now());
            broadcastUserErrorUpdate(user.getVrcUid(), e.getMessage());
        }
    }
    
    private void broadcastUserUpdate(String vrcUid, VRChatUser user) {
        UserStateService.UserState state = userStateService.getLatestUserState(vrcUid);
        if (state != null) {
            statusUpdateHandler.broadcastStatusUpdate(state);
        }
    }
    
    private void broadcastUserErrorUpdate(String vrcUid, String errorMessage) {
        UserStateService.UserState state = userStateService.getLatestUserState(vrcUid);
        if (state != null) {
            statusUpdateHandler.broadcastStatusUpdate(state);
        }
    }

    // Add a helper method to get the first user ID for session validation
    public String getFirstUserIdForValidation() {
        AppConfig config = configLoader.getConfig();
        if (config != null && config.getUsers() != null && !config.getUsers().isEmpty()) {
            UserConfig firstUser = config.getUsers().get(0);
            if (firstUser != null && firstUser.getVrcUid() != null && !firstUser.getVrcUid().isBlank()) {
                log.debug("Using user {} for session validation", firstUser.getHrToken());
                return firstUser.getVrcUid();
            }
        }
        return null;
    }
} 