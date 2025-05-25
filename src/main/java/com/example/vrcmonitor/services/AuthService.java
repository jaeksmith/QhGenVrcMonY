package com.example.vrcmonitor.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Console;
import java.util.Arrays;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private String username = null;
    // Password is not stored long-term anymore
    // private char[] password = null; 

    private final VRChatApiService vrchatApiService;

    // Inject VRChatApiService
    public AuthService(VRChatApiService vrchatApiService) {
        this.vrchatApiService = vrchatApiService;
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
} 