package com.example.vrcmonitor.web;

import com.example.vrcmonitor.models.dto.LoginRequestDTO;
import com.example.vrcmonitor.models.dto.LoginResultDTO;
import com.example.vrcmonitor.models.dto.SessionStatusDTO;
import com.example.vrcmonitor.services.AuthService;
import com.example.vrcmonitor.services.VRChatApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;
    private final StatusUpdateHandler statusUpdateHandler;

    public AuthController(AuthService authService, StatusUpdateHandler statusUpdateHandler) {
        this.authService = authService;
        this.statusUpdateHandler = statusUpdateHandler;
    }

    @GetMapping("/status")
    public ResponseEntity<SessionStatusDTO> getSessionStatus() {
        SessionStatusDTO status = new SessionStatusDTO(
            authService.hasActiveSession(),
            authService.getLastSessionTime(),
            authService.getUsername(),
            null
        );
        return ResponseEntity.ok(status);
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResultDTO>> login(@RequestBody LoginRequestDTO request) {
        log.info("Processing login request for user: {}", request.getUsername());
        
        if ("credentials".equals(request.getType())) {
            // Initial login with username/password
            return authService.clientLogin(request.getUsername(), request.getPassword())
                .map(result -> {
                    if (result == VRChatApiService.LoginResult.SUCCESS) {
                        // Login successful
                        statusUpdateHandler.broadcastSessionStatus();
                        return ResponseEntity.ok(LoginResultDTO.success());
                    } else if (result == VRChatApiService.LoginResult.REQUIRES_2FA) {
                        // 2FA required
                        return ResponseEntity.ok(new LoginResultDTO(
                            false,
                            true,
                            authService.get2FAType(),
                            "Two-factor authentication required",
                            result.name()
                        ));
                    } else {
                        // Login failed
                        return ResponseEntity.ok(new LoginResultDTO(
                            false,
                            false,
                            null,
                            getErrorMessageForLoginResult(result),
                            result.name()
                        ));
                    }
                });
        } else if ("2fa".equals(request.getType())) {
            // 2FA verification
            return authService.verify2FACode(request.getTwoFactorCode())
                .map(result -> {
                    if (result == VRChatApiService.LoginResult.SUCCESS) {
                        // 2FA verification successful
                        statusUpdateHandler.broadcastSessionStatus();
                        return ResponseEntity.ok(LoginResultDTO.success());
                    } else {
                        // 2FA verification failed
                        return ResponseEntity.ok(new LoginResultDTO(
                            false,
                            true,
                            authService.get2FAType(),
                            getErrorMessageForLoginResult(result),
                            result.name()
                        ));
                    }
                });
        } else {
            // Invalid request type
            return Mono.just(ResponseEntity.ok(new LoginResultDTO(
                false,
                false,
                null,
                "Invalid request type",
                "INVALID_REQUEST"
            )));
        }
    }
    
    private String getErrorMessageForLoginResult(VRChatApiService.LoginResult result) {
        return switch (result) {
            case FAILURE_CREDENTIALS -> "Invalid username or password";
            case FAILURE_2FA_INVALID_CODE -> "Invalid verification code";
            case FAILURE_NETWORK -> "Network error";
            case FAILURE_MISSING_AUTH_COOKIE -> "Authentication error";
            case FAILURE_UNSUPPORTED_2FA -> "Unsupported 2FA method";
            case FAILURE_2FA_VERIFICATION_FAILED -> "Verification failed";
            default -> "Unknown error";
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        log.info("Processing logout request");
        authService.logout();
        
        // Notify all clients about session status change
        statusUpdateHandler.broadcastSessionStatus();
        
        return ResponseEntity.ok().build();
    }
} 