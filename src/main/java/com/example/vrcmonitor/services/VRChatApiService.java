package com.example.vrcmonitor.services;

import com.example.vrcmonitor.models.CurrentUser;
import com.example.vrcmonitor.models.VRChatUser;
import com.example.vrcmonitor.models.dto.LogEntryDTO;
import com.example.vrcmonitor.models.dto.WsMessageDTO;
import com.example.vrcmonitor.web.StatusUpdateHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.net.SocketException;
import io.netty.channel.ConnectTimeoutException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import com.example.vrcmonitor.services.ApiRateLimiter;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.example.vrcmonitor.logging.ErrorFileLogger;
import com.example.vrcmonitor.services.SessionCacheManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Service
public class VRChatApiService {

    private static final Logger log = LoggerFactory.getLogger(VRChatApiService.class);
    private static final String VRC_API_BASE_URL = "https://api.vrchat.cloud/api/1";
    // Correct User-Agent as identified
    private static final String VRC_USER_AGENT = "VRC.Core.BestHTTP/2.2.1.0";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ApiRateLimiter apiRateLimiter;
    private final ErrorFileLogger errorFileLogger;
    private final SessionCacheManager sessionCacheManager;
    
    @Lazy
    @Autowired
    private StatusUpdateHandler statusUpdateHandler; // Used to broadcast logs

    @Getter
    private String authCookie = null; // Holds the current auth cookie (temporary or final)
    // Add field to store the 2FA cookie received after successful verification
    private String twoFactorAuthCookie = null;
    @Getter
    private String required2faType = null;

    // Retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 7; // Example: allows delays up to 64s, then hits max
    private static final Duration MIN_RETRY_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(10);

    public VRChatApiService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, ApiRateLimiter apiRateLimiter, ErrorFileLogger errorFileLogger,
                           SessionCacheManager sessionCacheManager) {
        this.objectMapper = objectMapper;
        this.apiRateLimiter = apiRateLimiter;
        this.errorFileLogger = errorFileLogger;
        this.sessionCacheManager = sessionCacheManager;
        
        // Create filter functions to log requests and responses
        ExchangeFilterFunction requestLoggingFilter = ExchangeFilterFunction.ofRequestProcessor(request -> {
            logRequest(request);
            return Mono.just(request);
        });
        
        // Create a filter to log responses with their bodies
        ExchangeFilterFunction responseLoggingFilter = ExchangeFilterFunction.ofResponseProcessor(response -> {
            // For headers and status
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("Status: ").append(response.statusCode().value());
            
            // Add headers
            logBuilder.append("\nHeaders: ");
            response.headers().asHttpHeaders().forEach((name, values) -> {
                values.forEach(value -> {
                    logBuilder.append("\n  ").append(name).append(": ").append(value);
                });
            });
            
            // Create a Mono that will log the body and then return the original response
            return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    // Add body to log if present
                    if (!body.isEmpty()) {
                        logBuilder.append("\nBody: ").append(body);
                    }
                    
                    // Sanitize and log
                    String sanitizedLog = sanitizeLogContent(logBuilder.toString());
                    log.debug("VRChat API Response: {}", sanitizedLog);
                    
                    // Broadcast to clients if handler is available
                    try {
                        if (statusUpdateHandler != null) {
                            LogEntryDTO logEntry = new LogEntryDTO("response", sanitizedLog, Instant.now());
                            statusUpdateHandler.broadcastLogEntry(logEntry);
                        } else {
                            log.debug("statusUpdateHandler is null, cannot broadcast response log");
                        }
                    } catch (Exception e) {
                        log.error("Error broadcasting response log: {}", e.getMessage(), e);
                        errorFileLogger.logError("Error broadcasting response log", e);
                    }
                    
                    // Return the original body text
                    return body;
                })
                .switchIfEmpty(Mono.just(""))
                .flatMap(body -> {
                    // Recreate the response with same status, headers, cookies, etc.
                    // but with the body we already consumed
                    ClientResponse.Builder builder = ClientResponse.create(response.statusCode());
                    response.headers().asHttpHeaders().forEach((name, values) -> 
                        builder.header(name, values.toArray(new String[0])));
                    response.cookies().forEach((name, cookies) -> 
                        cookies.forEach(cookie -> builder.cookie(name, cookie.getValue())));
                    
                    // Set the body if we have one
                    if (!body.isEmpty()) {
                        builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                        return Mono.just(builder.body(body).build());
                    } else {
                        return Mono.just(builder.build());
                    }
                });
        });
        
        this.webClient = webClientBuilder.baseUrl(VRC_API_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, VRC_USER_AGENT)
                // Configure cookie handling (should be default, but explicit doesn't hurt)
                .codecs(configurer -> configurer.defaultCodecs().enableLoggingRequestDetails(true)) // Enable for debugging if needed
                .filter(requestLoggingFilter)
                .filter(responseLoggingFilter)
                .build();
                
        log.debug("VRChatApiService using injected ObjectMapper: {}", objectMapper.hashCode());
        
        // Try to restore session from cache on startup
        tryRestoreSessionFromCache();
    }
    
    /**
     * Logs an API request and broadcasts it to clients if a WebSocket handler is available
     */
    private void logRequest(ClientRequest request) {
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append(request.method().name()).append(" ").append(request.url().toString());
        
        // Add headers
        if (!request.headers().isEmpty()) {
            logBuilder.append("\nHeaders: ");
            request.headers().forEach((name, values) -> {
                values.forEach(value -> {
                    logBuilder.append("\n  ").append(name).append(": ").append(value);
                });
            });
        }
        
        // Log body if available (this might be limited due to how WebClient works)
        // Note: This is simplified as full body logging requires more complex setup
        
        String sanitizedLog = sanitizeLogContent(logBuilder.toString());
        log.debug("VRChat API Request: {}", sanitizedLog);
        
        // Broadcast to clients if handler is available
        try {
            if (statusUpdateHandler != null) {
                LogEntryDTO logEntry = new LogEntryDTO("request", sanitizedLog, Instant.now());
                statusUpdateHandler.broadcastLogEntry(logEntry);
            } else {
                log.debug("statusUpdateHandler is null, cannot broadcast request log");
            }
        } catch (Exception e) {
            log.error("Error broadcasting request log: {}", e.getMessage(), e);
            errorFileLogger.logError("Error broadcasting request log", e);
        }
    }
    
    /**
     * Sanitizes log content to remove sensitive information
     */
    private String sanitizeLogContent(String content) {
        if (content == null) return null;
        
        // List of patterns to sanitize
        List<Pattern> sensitivePatterns = List.of(
            // Password
            Pattern.compile("(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(password=)[^&\\s]*"),
            
            // Auth cookies and tokens
            Pattern.compile("(?i)(\"auth(?:Token|Cookie)?\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(auth(?:Token|Cookie)?=)[^&\\s]*"),
            Pattern.compile("(?i)(auth:\\s*)[^\\s,\\}]*"),
            
            // Session tokens
            Pattern.compile("(?i)(\"session(?:Token|Id)?\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(session(?:Token|Id)?=)[^&\\s]*"),
            
            // API keys
            Pattern.compile("(?i)(\"api[_-]?key\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(api[_-]?key=)[^&\\s]*"),
            
            // 2FA cookies
            Pattern.compile("(?i)(\"twoFactorAuth(?:Token|Cookie)?\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(twoFactorAuth(?:Token|Cookie)?=)[^&\\s]*"),
            
            // Basic auth header
            Pattern.compile("(?i)(Basic\\s+)[A-Za-z0-9+/=]+"),
            
            // Bearer tokens
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9_.-]+"),
            
            // Cookie header often contains sensitive tokens
            Pattern.compile("(?i)(Cookie:\\s*)[^\\n]*"),
            
            // User credentials in JSON
            Pattern.compile("(?i)(\"username\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"email\"\\s*:\\s*\")[^\"]*(\")"),
            
            // Other potential sensitive fields
            Pattern.compile("(?i)(\"private\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"secret\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"key\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"token\"\\s*:\\s*\")[^\"]*(\")"),
            
            // VRChat specific
            Pattern.compile("(?i)(\"currentAvatarImageUrl\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"currentAvatarThumbnailImageUrl\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"userIcon\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"profilePicOverride\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"fallbackAvatar\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"imageUrl\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"thumbnailImageUrl\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"url\"\\s*:\\s*\")[^\"]*(\")"),
            Pattern.compile("(?i)(\"tags\"\\s*:\\s*\\[)[^\\]]*?(\\])") // Tags can contain sensitive info
        );
        
        String result = content;
        for (Pattern pattern : sensitivePatterns) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                if (matcher.groupCount() == 2) {
                    // Replace only the value part between the capturing groups
                    result = matcher.replaceAll("$1###REDACTED###$2");
                } else {
                    // Replace the entire matched pattern
                    result = matcher.replaceAll("$1###REDACTED###");
                }
            }
        }
        
        return result;
    }

    /**
     * Attempts to restore a previously saved session from the cache file.
     * Only runs once during initialization.
     */
    private void tryRestoreSessionFromCache() {
        Map<String, String> sessionData = sessionCacheManager.loadSessionCache();
        if (sessionData != null) {
            String cachedAuthCookie = sessionData.get("authCookie");
            String cachedTwoFactorAuthCookie = sessionData.get("twoFactorAuthCookie");
            
            if (cachedAuthCookie != null && !cachedAuthCookie.isEmpty()) {
                log.info("Restoring auth cookie from session cache");
                this.authCookie = cachedAuthCookie;
                
                if (cachedTwoFactorAuthCookie != null && !cachedTwoFactorAuthCookie.isEmpty()) {
                    log.info("Restoring two-factor auth cookie from session cache");
                    this.twoFactorAuthCookie = cachedTwoFactorAuthCookie;
                }
                
                // The session will be tested with the first API request
                // If it's invalid, it will be cleared automatically
            }
        }
    }

    public Mono<LoginResult> login(String username, char[] password) {
        String credentials = username + ":" + new String(password);
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        clearPassword(password);

        this.authCookie = null; // Reset before login
        this.twoFactorAuthCookie = null; // Reset 2FA cookie
        this.required2faType = null;

        // Step 1: Initial /auth/user check
        return webClient.get()
                .uri("/auth/user")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials)
                .exchangeToMono(response -> {
                    // Log initial response details
                    log.debug("Initial /auth/user response Status: {}", response.statusCode());
                    log.debug("Initial /auth/user response Headers: {}", response.headers().asHttpHeaders());

                    // Store the initial auth cookie - it's needed even if 2FA is required
                    ResponseCookie initialAuthCookie = response.cookies().getFirst("auth");
                    if (initialAuthCookie != null) {
                        this.authCookie = initialAuthCookie.getValue();
                        log.debug("Stored initial 'auth' cookie from /auth/user response.");
                        
                        // Process successful responses (2xx status codes)
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(String.class).flatMap(rawBody -> {
                                log.debug("Initial /auth/user response Body: {}", rawBody);
                                CurrentUser currentUser;
                                try { 
                                    currentUser = objectMapper.readValue(rawBody, CurrentUser.class);
                                } catch (Exception e) {
                                    log.error("Error parsing CurrentUser JSON: {}", e.getMessage());
                                    this.authCookie = null; 
                                    return Mono.just(LoginResult.FAILURE_NETWORK);
                                }

                                if (currentUser.getRequiresTwoFactorAuth() != null && !currentUser.getRequiresTwoFactorAuth().isEmpty()) {
                                    List<String> twoFactorTypes = currentUser.getRequiresTwoFactorAuth();
                                    log.info("Login requires 2FA. Supported types: {}", twoFactorTypes);
                                    this.required2faType = twoFactorTypes.contains("totp") ? "totp" : twoFactorTypes.get(0);
                                    log.info("Selected 2FA type: {}. Returning REQUIRES_2FA result.", this.required2faType);

                                    // Initial auth cookie must exist to proceed
                                    if (this.authCookie == null) { 
                                         log.error("INTERNAL ERROR: 2FA required, but initial 'auth' cookie is missing. Cannot proceed.");
                                         return Mono.just(LoginResult.FAILURE_MISSING_AUTH_COOKIE); 
                                    }

                                    // Return that 2FA is required - client will handle collecting the code
                                    return Mono.just(LoginResult.REQUIRES_2FA);
                                } else {
                                    // Login successful, 2FA not required - save to cache
                                    sessionCacheManager.saveSessionCache(this.authCookie, null);
                                    log.info("VRChat login successful (No 2FA). Using 'auth' cookie from initial response.");
                                    return Mono.just(LoginResult.SUCCESS);
                                }
                            });
                        } else {
                            // Handle non-2xx status with auth cookie present
                            // This is unusual but possible - we got an auth cookie but response indicates failure
                            // In this case, we don't trust the auth cookie and clear it
                            this.authCookie = null;
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("[No error body]")
                                    .map(body -> {
                                        log.warn("VRChat initial login returned non-2xx with auth cookie. Status: {}, Body: {}", 
                                                response.statusCode(), body);
                                        return LoginResult.FAILURE_CREDENTIALS;
                                    });
                        }
                        
                    } else {
                        // No auth cookie received (common for authentication failures)
                        log.error("Initial 'auth' cookie was MISSING from /auth/user response. Cannot proceed.");
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("[No error body]")
                                .map(body -> {
                                    log.warn("VRChat login failed, no auth cookie. Status: {}, Body: {}", 
                                            response.statusCode(), body);
                                    return LoginResult.FAILURE_CREDENTIALS;
                                });
                    }
                })
                .onErrorResume(error -> {
                    this.authCookie = null; 
                    log.error("Error during VRChat initial login request: {}", error.getMessage(), error);
                    return Mono.just(LoginResult.FAILURE_NETWORK);
                });
    }

    /**
     * Verifies a 2FA code provided by the client
     * @param code The 2FA code to verify
     * @return Result of the verification
     */
    public Mono<LoginResult> verify2FACode(String code) {
        if (code == null || code.isBlank()) {
            log.warn("No 2FA code provided.");
            return Mono.just(LoginResult.FAILURE_2FA_INVALID_CODE);
        }
        
        if (this.authCookie == null) {
            log.error("INTERNAL ERROR: Cannot verify 2FA code, initial 'auth' cookie is missing.");
            return Mono.just(LoginResult.FAILURE_MISSING_AUTH_COOKIE);
        }
        
        if (this.required2faType == null) {
            log.error("INTERNAL ERROR: Cannot verify 2FA code, no 2FA type is set.");
            return Mono.just(LoginResult.FAILURE_UNSUPPORTED_2FA);
        }
        
        final String initialAuthCookieValue = this.authCookie;
        // Don't clear the auth cookie before verification, keep it until we have a confirmed new one
        // this.authCookie = null; -- REMOVE THIS LINE

        String verificationUri;
        if ("emailOtp".equalsIgnoreCase(this.required2faType)) {
            verificationUri = "/auth/twofactorauth/emailotp/verify";
        } else if ("totp".equalsIgnoreCase(this.required2faType)) {
            verificationUri = "/auth/twofactorauth/totp/verify";
        } else {
            log.error("Unsupported 2FA type for verification: {}", this.required2faType);
            return Mono.just(LoginResult.FAILURE_UNSUPPORTED_2FA);
        }

        log.info("Submitting 2FA code to {}...", verificationUri);
        Map<String, Object> requestBody = Map.of("code", code);
        log.debug("Sending POST {} Request Body: {}", verificationUri, requestBody);

        return webClient.post()
                .uri(verificationUri)
                .cookie("auth", initialAuthCookieValue) // Send the initial auth cookie
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(requestBody))
                .exchangeToMono(response -> {
                    log.debug("POST {} response Status: {}", verificationUri, response.statusCode());
                    log.debug("POST {} response Headers: {}", verificationUri, response.headers().asHttpHeaders());

                    if (response.statusCode().is2xxSuccessful()) {
                        // Successful 2FA: Store BOTH final auth and 2FA cookie
                        ResponseCookie finalAuthCookie = response.cookies().getFirst("auth");
                        ResponseCookie received2faCookie = response.cookies().getFirst("twoFactorAuth"); // Extract 2FA cookie

                        if (finalAuthCookie != null) {
                            this.authCookie = finalAuthCookie.getValue();
                            log.debug("Stored final 'auth' cookie from verification response.");
                        } else {
                            // If no new auth cookie, maybe the initial one is still valid? Re-use it.
                             log.warn("No new 'auth' cookie in verification response. Re-using initial one.");
                             this.authCookie = initialAuthCookieValue;
                        }

                        if (received2faCookie != null) {
                            this.twoFactorAuthCookie = received2faCookie.getValue(); // Store the 2FA cookie
                            log.debug("Stored 'twoFactorAuth' cookie from verification response.");
                        } else {
                             // This might be okay, but log a warning
                             log.warn("'twoFactorAuth' cookie was MISSING from successful verification response.");
                        }
                        
                        // Save the complete session to cache after successful 2FA
                        sessionCacheManager.saveSessionCache(this.authCookie, this.twoFactorAuthCookie);
                        
                        log.info("VRChat 2FA verification successful.");
                        return response.bodyToMono(String.class).thenReturn(LoginResult.SUCCESS);

                    } else {
                        // Handle 2FA failure - clear auth cookies and cache
                        this.authCookie = null;
                        this.twoFactorAuthCookie = null;
                        sessionCacheManager.clearSessionCache();
                         return response.bodyToMono(String.class)
                                .defaultIfEmpty("[No error body]")
                                .map(body -> {
                                    log.warn("VRChat 2FA verification failed. Status: {}, Body: {}", response.statusCode(), body);
                                    if (body.toLowerCase().contains("invalid code")) {
                                        return LoginResult.FAILURE_2FA_INVALID_CODE;
                                    } else {
                                         return LoginResult.FAILURE_2FA_VERIFICATION_FAILED;
                                    }
                                });
                    }
                })
                .onErrorResume(error -> {
                    this.authCookie = null; // Clear cookies on network error
                    this.twoFactorAuthCookie = null;
                    log.error("Error during VRChat 2FA submission: {}", error.getMessage(), error);
                    return Mono.just(LoginResult.FAILURE_NETWORK);
                });
    }

    /**
     * Logs API request details for debugging
     * 
     * @param method HTTP method (GET, POST, etc.)
     * @param uri The URI being requested
     */
    private void logRequestDetails(String method, String uri) {
        if (log.isDebugEnabled()) {
            StringBuilder logMessage = new StringBuilder();
            logMessage.append("VRChat API Request: ").append(method).append(" https://api.vrchat.cloud/api/1").append(uri);
            logMessage.append("\nHeaders:\n  User-Agent: ").append(VRC_USER_AGENT);
            
            // Don't log the actual cookie values for security
            logMessage.append("\n  Cookie: ###REDACTED###");
            
            log.debug(logMessage.toString());
            
            // Also broadcast to websocket if handler is available
            if (statusUpdateHandler != null) {
                statusUpdateHandler.broadcastClientRequest(method + " " + uri);
            }
        }
    }
    
    public Mono<VRChatUser> getUserByUid(String vrcUid) {
        if (authCookie == null) {
            log.warn("Auth Cookie not available. Cannot fetch user ID: {}. Please login again.", vrcUid);
            return Mono.empty();
        }

        // Define the core request logic as a deferred Mono
        return Mono.fromCallable(() -> {
            // Check auth cookie again inside the deferred execution
            // to catch any auth cookie that was cleared by another request
            if (authCookie == null) {
                log.warn("Auth Cookie was cleared during wait. Cannot fetch user ID: {}. Please login again.", vrcUid);
                return null;
            }

            // Apply rate limiting before making the API call
            try {
                apiRateLimiter.waitForThrottlingConstraints();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Rate limiting wait interrupted", e);
            }
        })
        .flatMap(readyToFetch -> {
            if (readyToFetch == null) {
                return Mono.empty();
            }
            
            log.debug("Fetching user data for: {}", vrcUid);
            
            MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
            cookies.add("auth", authCookie);
            if (twoFactorAuthCookie != null) {
                cookies.add("twoFactorAuth", twoFactorAuthCookie);
            }
            
            return webClient.get()
                .uri("/users/" + vrcUid)
                .cookies(cookiesMap -> cookiesMap.addAll(cookies))
                .header(HttpHeaders.USER_AGENT, VRC_USER_AGENT)
                .exchangeToMono(response -> {
                    // Log request details for debugging
                    logRequestDetails("GET", "/users/" + vrcUid);
                    
                    // Process response based on status code
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(VRChatUser.class)
                            .doOnError(e -> log.error("Error parsing user response: {}", e.getMessage()));
                    } else {
                        // For error responses, try to extract error message from body
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("{}")
                            .flatMap(body -> {
                                log.error("Error response for user {}: Status: {}, Body: {}", 
                                         vrcUid, response.statusCode().value(), body);
                                
                                if (response.statusCode().equals(HttpStatusCode.valueOf(401))) {
                                    log.warn("Authentication failed (401) for user {}, clearing session", vrcUid);
                                    logout(); // Clear session cookies
                                    return Mono.error(new AuthenticationException("Authentication error"));
                                } else {
                                    return Mono.error(new ApiException(
                                        "API Error " + response.statusCode().value() + ": " + body, 
                                        response.statusCode().value()));
                                }
                            });
                    }
                })
                .doFinally(signalType -> {
                    // Record completion time when the request finishes (success or error)
                    apiRateLimiter.recordRequestFinished();
                    log.debug("Request for user {} completed with signal: {}", vrcUid, signalType);
                })
                // Add retry logic for network-related errors only
                .retryWhen(Retry.backoff(MAX_RETRY_ATTEMPTS, MIN_RETRY_BACKOFF)
                    .maxBackoff(MAX_RETRY_BACKOFF)
                    .filter(throwable -> {
                        // Only retry on network-related errors, not authentication or API errors
                        if (throwable instanceof AuthenticationException || throwable instanceof ApiException) {
                            log.debug("Not retrying authentication/API error for user {}: {}", vrcUid, throwable.getMessage());
                            return false;
                        }
                        
                        // Retry on network-related errors
                        boolean shouldRetry = isRetryableError(throwable);
                        if (shouldRetry) {
                            log.debug("Retrying request for user {} due to network error: {}", vrcUid, throwable.getMessage());
                        } else {
                            log.debug("Not retrying request for user {} - not a retryable error: {}", vrcUid, throwable.getMessage());
                        }
                        return shouldRetry;
                    })
                    .doBeforeRetry(retrySignal -> {
                        log.warn("Retrying request for user {} (attempt {}/{}): {}", 
                                vrcUid, retrySignal.totalRetries() + 1, MAX_RETRY_ATTEMPTS + 1, 
                                retrySignal.failure().getMessage());
                    })
                );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Helper method to determine if an error is retryable
    private boolean isRetryableError(Throwable throwable) {
        return (throwable instanceof SocketException ||
                throwable instanceof IOException || 
                throwable instanceof ConnectTimeoutException || 
                // Check if WebClient exception is caused by a retryable type
                (throwable instanceof WebClientRequestException && throwable.getCause() != null && isRetryableError(throwable.getCause())));
    }

    public void logout() {
        this.authCookie = null;
        this.twoFactorAuthCookie = null; // Clear 2FA cookie too
        this.required2faType = null;
        
        // Clear the session cache when explicitly logging out
        sessionCacheManager.clearSessionCache();
        
        log.info("Local auth cookies cleared and session cache removed.");
    }

    // Utility to safely clear password array
    public static void clearPassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, ' ');
        }
    }

    // Enum to represent distinct login outcomes/states
    public enum LoginResult {
        SUCCESS,
        FAILURE_CREDENTIALS,
        FAILURE_2FA_INVALID_CODE,
        FAILURE_2FA_VERIFICATION_FAILED, // General failure during POST verify step
        FAILURE_MISSING_AUTH_COOKIE, // Specifically for the *initial* auth cookie needed for 2FA verify
        FAILURE_CONSOLE_UNAVAILABLE,
        FAILURE_UNSUPPORTED_2FA,
        FAILURE_NETWORK,
        REQUIRES_2FA         // New status to indicate client needs to provide 2FA code
    }

    /**
     * Check if there's an active session (auth cookie exists)
     * @return true if an active session exists
     */
    public boolean hasActiveSession() {
        return authCookie != null;
    }

    /**
     * Gets the currently logged-in user's profile.
     * This is used to validate if a session is still active.
     * 
     * @return Mono with the current user's profile or empty if not authenticated
     */
    public Mono<VRChatUser> getCurrentUser() {
        if (authCookie == null) {
            log.warn("Auth Cookie not available. Cannot get current user. Please login again.");
            return Mono.empty();
        }

        // Use Mono.fromCallable with proper rate limiting
        return Mono.fromCallable(() -> {
            try {
                apiRateLimiter.waitForThrottlingConstraints();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Rate limiting wait interrupted", e);
            }
        })
        .flatMap(readyToFetch -> {
            // Check auth cookie again inside the deferred execution
            if (authCookie == null) {
                log.warn("Auth Cookie was cleared during wait. Cannot get current user. Please login again.");
                return Mono.empty();
            }
            
            log.debug("Fetching current user profile");
            
            MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
            cookies.add("auth", authCookie);
            if (twoFactorAuthCookie != null) {
                cookies.add("twoFactorAuth", twoFactorAuthCookie);
            }
            
            return webClient.get()
                .uri("/auth/user")
                .cookies(cookiesMap -> cookiesMap.addAll(cookies))
                .header(HttpHeaders.USER_AGENT, VRC_USER_AGENT)
                .exchangeToMono(response -> {
                    // Log request details for debugging
                    logRequestDetails("GET", "/auth/user");
                    
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(VRChatUser.class)
                            .doOnError(error -> {
                                log.error("Error parsing current user data: {}", error.getMessage());
                            });
                    } else if (response.statusCode().equals(HttpStatusCode.valueOf(401))) {
                        log.warn("Authentication expired. Session invalid.");
                        logout(); // Clear session since it's invalid
                        return Mono.empty();
                    } else {
                        // Other errors
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("{}")
                            .flatMap(body -> {
                                log.error("Error getting current user. Status: {}, Body: {}", 
                                        response.statusCode().value(), body);
                                return Mono.empty();
                            });
                    }
                })
                .doFinally(signalType -> {
                    // Record completion time when the request finishes
                    apiRateLimiter.recordRequestFinished();
                    log.debug("Current user request completed with signal: {}", signalType);
                });
        })
        .subscribeOn(Schedulers.boundedElastic());
    }

    // Custom exception classes for proper error classification
    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
        
        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class ApiException extends RuntimeException {
        private final int statusCode;
        
        public ApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        
        public ApiException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
    }
} 