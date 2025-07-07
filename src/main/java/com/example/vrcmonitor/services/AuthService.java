package com.example.vrcmonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import jakarta.annotation.PostConstruct;
import com.example.vrcmonitor.web.StatusUpdateHandler;

import java.io.Console;
import java.util.Arrays;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private String username = null;
    // Password is not stored long-term anymore
    // private char[] password = null; 
    
    // Add session tracking
    private AtomicBoolean hasActiveSession = new AtomicBoolean(false);
    private Instant lastSessionTime = null;

    private final VRChatApiService vrchatApiService;
    private final ApplicationContext applicationContext;  // Use ApplicationContext instead of direct reference

    // Inject VRChatApiService and ApplicationContext
    public AuthService(VRChatApiService vrchatApiService, ApplicationContext applicationContext) {
        this.vrchatApiService = vrchatApiService;
        this.applicationContext = applicationContext;
    }

    /**
     * Validates the session on application startup.
     * If a session was restored from cache, this will verify if it's still valid
     * by making a test API call to VRChat.
     */
    @PostConstruct
    public void validateRestoredSession() {
        // Check if we have auth cookies (restored from cache)
        if (vrchatApiService.getAuthCookie() != null) {
            log.info("Session cookies found, validating restored session...");
            
            // Get a valid user ID for validation
            String testUserId = null;
            
            // Safer way to check if the cookie is valid - just do a self user lookup
            // which should work with any valid session
            try {
                // Use test API call to validate with self user endpoint instead
                vrchatApiService.getCurrentUser()
                    .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofSeconds(2))
                        .maxBackoff(java.time.Duration.ofSeconds(10))
                        .filter(throwable -> {
                            // Only retry on network-related errors, not authentication errors
                            if (throwable instanceof VRChatApiService.AuthenticationException) {
                                log.debug("Not retrying authentication error during session validation");
                                return false;
                            }
                            
                            // Retry on network-related errors
                            boolean shouldRetry = isRetryableError(throwable);
                            if (shouldRetry) {
                                log.debug("Retrying session validation due to network error: {}", throwable.getMessage());
                            }
                            return shouldRetry;
                        })
                        .doBeforeRetry(retrySignal -> {
                            log.warn("Retrying session validation (attempt {}/3): {}", 
                                    retrySignal.totalRetries() + 1, retrySignal.failure().getMessage());
                        })
                    )
                    .doOnNext(user -> {
                        // If we got a response without error, the session is valid
                        log.info("Restored session is valid, user: {}", user.getDisplayName());
                        hasActiveSession.set(true);
                        lastSessionTime = Instant.now();
                        startMonitoring();
                        
                        // Broadcast the session status to all clients after slight delay
                        // to ensure WebSocket connections are established
                        try {
                            // Short delay to allow WebSocket connections to be established
                            Thread.sleep(1000);
                            
                            // Get StatusUpdateHandler to broadcast session status
                            try {
                                StatusUpdateHandler statusHandler = applicationContext.getBean(StatusUpdateHandler.class);
                                statusHandler.broadcastSessionStatus();
                                log.debug("Session status broadcast after successful session restoration");
                            } catch (Exception e) {
                                log.warn("Could not broadcast session status: {}", e.getMessage());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Session status broadcast delay interrupted");
                        }
                    })
                    .doOnError(error -> {
                        // If we got an authentication error, the session is invalid
                        log.warn("Restored session validation failed: {}", error.getMessage());
                        hasActiveSession.set(false);
                        // Clear the invalid session
                        vrchatApiService.logout();
                    })
                    .onErrorResume(e -> Mono.empty()) // Don't propagate errors
                    .subscribe();
            } catch (Exception e) {
                log.error("Error validating restored session: {}", e.getMessage());
            }
        } else {
            log.debug("No session cookies found, skipping validation");
        }
    }

    // Helper method to start monitoring via ApplicationContext to avoid circular dependency
    private void startMonitoring() {
        try {
            MonitoringService monitoringService = applicationContext.getBean(MonitoringService.class);
            monitoringService.startMonitoring();
        } catch (Exception e) {
            log.error("Failed to start monitoring: {}", e.getMessage());
        }
    }

    // Use real VRChat API interaction. Returns specific result code.
    private VRChatApiService.LoginResult attemptLogin(String user, char[] pass) {
        log.info("Attempting VRChat login for user: {}", user);
        // Block until the reactive call completes (including potential 2FA prompt within API service)
        VRChatApiService.LoginResult result = vrchatApiService.login(user, pass).block();
        // Password char array is cleared within vrchatApiService.login or its sub-methods now
        return result != null ? result : VRChatApiService.LoginResult.FAILURE_NETWORK; // Handle null block result
    }

    /**
     * @return true if there is an active session
     */
    public boolean hasActiveSession() {
        return hasActiveSession.get();
    }
    
    /**
     * @return the time of last active session
     */
    public Instant getLastSessionTime() {
        return lastSessionTime;
    }
    
    /**
     * Perform a login using credentials from client UI
     * @param username VRChat username
     * @param password Password as string
     * @return Mono with login result
     */
    public Mono<VRChatApiService.LoginResult> clientLogin(String username, String password) {
        log.info("Attempting VRChat login for user: {}", username);
        
        // Convert string to char array for security
        char[] passwordChars = password != null ? password.toCharArray() : new char[0];
        
        try {
            this.username = username;
            return vrchatApiService.login(username, passwordChars)
                .doOnSuccess(result -> {
                    if (result == VRChatApiService.LoginResult.SUCCESS) {
                        hasActiveSession.set(true);
                        lastSessionTime = Instant.now();
                        startMonitoring(); // Use the helper method
                        log.info("Authentication successful via client login.");
                    }
                });
        } finally {
            // Always clear password chars for security
            VRChatApiService.clearPassword(passwordChars);
        }
    }
    
    /**
     * Verify 2FA code from client UI
     * @param code The 2FA code entered by user
     * @return Mono with login result
     */
    public Mono<VRChatApiService.LoginResult> verify2FACode(String code) {
        return vrchatApiService.verify2FACode(code)
            .doOnSuccess(result -> {
                if (result == VRChatApiService.LoginResult.SUCCESS) {
                    hasActiveSession.set(true);
                    lastSessionTime = Instant.now();
                    startMonitoring(); // Use the helper method
                    log.info("2FA verification successful via client.");
                }
            });
    }
    
    /**
     * Get the required 2FA type
     * @return The 2FA type or null if not required
     */
    public String get2FAType() {
        return vrchatApiService.getRequired2faType();
    }
    
    /**
     * Clears the active session
     */
    public void logout() {
        vrchatApiService.logout();
        this.username = null;
        this.hasActiveSession.set(false);
        log.info("User logged out. Session cleared.");
    }

    /**
     * Performs the console login flow, including retries and potential 2FA.
     * @return true if login succeeds, false if it fails definitively after retries or due to fatal error.
     */
    public boolean performConsoleLogin() {
        Console console = System.console();
        if (console == null) {
            log.error("FATAL: No console available. Cannot securely read password or 2FA code.");
            return false; // Indicate fatal failure
        }

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Use System.out directly for prompts, as logging framework might buffer
            System.out.println("\nPlease enter VRChat credentials (attempt " + attempt + "/" + maxAttempts + "):");
            String currentUsername = console.readLine("Username: ");
            char[] currentPassword = console.readPassword("Password: ");

            if (currentUsername == null || currentUsername.isBlank() || currentPassword == null || currentPassword.length == 0) {
                log.warn("Username and password cannot be empty.");
                VRChatApiService.clearPassword(currentPassword); // Clear potentially partially read password
                continue; // Try again
            }

            VRChatApiService.LoginResult loginResult = attemptLogin(currentUsername, currentPassword);
            // Password array is cleared within attemptLogin/VRChatApiService

            // Use unqualified enum constant names in case labels
            switch (loginResult) {
                case SUCCESS:
                    log.info("Authentication successful via AuthService.");
                    this.username = currentUsername; // Store username for reference
                    this.hasActiveSession.set(true);
                    this.lastSessionTime = Instant.now();
                    return true; // Overall success
                case FAILURE_CREDENTIALS:
                    log.warn("Login failed (invalid username or password). Please try again.");
                    break; // Continue to next attempt
                case FAILURE_2FA_INVALID_CODE:
                    log.warn("Login failed (invalid 2FA code). Please try again.");
                    break; // Continue to next attempt (will re-prompt for credentials)
                // Handle fatal errors that should stop the process immediately
                case FAILURE_2FA_VERIFICATION_FAILED:
                    log.error("Login failed: 2FA verification failed (API error).");
                    return false; // Treat as fatal error for now
                case FAILURE_CONSOLE_UNAVAILABLE:
                    log.error("Login failed: Console became unavailable during 2FA.");
                    return false;
                case FAILURE_NETWORK:
                    log.error("Login failed: Network error during authentication.");
                    return false;
                case FAILURE_MISSING_AUTH_COOKIE:
                    log.error("Login failed: Internal authentication error (missing initial auth cookie).");
                    return false;
                case FAILURE_UNSUPPORTED_2FA:
                    log.error("Login failed: Unsupported 2FA type reported by API.");
                    return false;
                default:
                    log.error("Login failed: Unexpected authentication result: {}", loginResult);
                    return false;
            }
            // If we reach here, it was a recoverable failure (wrong creds/2fa), loop continues
        }

        log.error("Authentication failed after maximum attempts.");
        clearCredentials(); // Ensure cleanup on final failure
        return false; // Failed after max attempts
    }

    // Method to clear stored credentials
    public void clearCredentials() {
        this.username = null;
        // Password array is no longer stored here
        // if (this.password != null) {
        //     Arrays.fill(this.password, ' ');
        //     this.password = null;
        // }
        // Also log out from VRChat API Service
        vrchatApiService.logout();
        log.info("Stored local username cleared and VRChat API logout performed.");
    }

    // Methods to potentially access credentials (use with caution)
    public String getUsername() {
        // Username is still stored locally for reference if needed
        return username;
    }

    // Password is not stored locally anymore for better security
    // public char[] getPassword() {
    //    return null;
    // }

    // Check if we have an auth cookie in the API service
    public boolean hasCredentials() {
        return vrchatApiService.getAuthCookie() != null;
    }

    // Helper method to determine if an error is retryable (copied from VRChatApiService)
    private boolean isRetryableError(Throwable throwable) {
        return (throwable instanceof java.net.SocketException ||
                throwable instanceof java.io.IOException || 
                throwable instanceof io.netty.channel.ConnectTimeoutException || 
                // Check if WebClient exception is caused by a retryable type
                (throwable instanceof org.springframework.web.reactive.function.client.WebClientRequestException && 
                 throwable.getCause() != null && isRetryableError(throwable.getCause())));
    }
} 