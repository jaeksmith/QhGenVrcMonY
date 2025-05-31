package com.example.vrcmonitor.web;

import com.example.vrcmonitor.VrcMonitorApplication;
import com.example.vrcmonitor.config.AppConfig;
import com.example.vrcmonitor.config.ConfigLoader;
import com.example.vrcmonitor.config.UserConfig;
import com.example.vrcmonitor.models.dto.LogEntryDTO;
import com.example.vrcmonitor.models.dto.SessionStatusDTO;
import com.example.vrcmonitor.models.dto.StatusUpdateDTO;
import com.example.vrcmonitor.models.dto.WsMessageDTO;
import com.example.vrcmonitor.services.AuthService;
import com.example.vrcmonitor.services.UserStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class StatusUpdateHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StatusUpdateHandler.class);
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final UserStateService userStateService;
    private final ConfigLoader configLoader; // To get HRTokens
    private final ObjectMapper objectMapper; // Use the configured one
    private final AuthService authService; // For session status
    private final Object broadcastLock = new Object();

    public StatusUpdateHandler(UserStateService userStateService, ConfigLoader configLoader, ObjectMapper objectMapper, @Lazy AuthService authService) {
        this.userStateService = userStateService;
        this.configLoader = configLoader;
        this.objectMapper = objectMapper;
        this.authService = authService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket connection established: SessionId={}, RemoteAddress={}", session.getId(), session.getRemoteAddress());
        
        // First send session status
        sendSessionStatus(session);
        
        // Always send initial state, even when not logged in
        sendInitialState(session);
    }

    private void sendSessionStatus(WebSocketSession session) {
        SessionStatusDTO sessionStatus = new SessionStatusDTO(
                authService.hasActiveSession(),
                authService.getLastSessionTime(),
                null, // Don't expose username
                null
        );
        
        WsMessageDTO message = new WsMessageDTO(
                WsMessageDTO.MessageType.SESSION_STATUS,
                sessionStatus
        );
        
        sendMessage(session, message);
    }

    /**
     * Broadcasts the current session status to all connected clients
     */
    public void broadcastSessionStatus() {
        log.info("Broadcasting session status: hasActiveSession={}, lastSessionTime={}", 
                authService.hasActiveSession(), authService.getLastSessionTime());
        
        SessionStatusDTO sessionStatus = new SessionStatusDTO(
                authService.hasActiveSession(),
                authService.getLastSessionTime(),
                null, // Don't expose username
                null
        );
        
        WsMessageDTO message = new WsMessageDTO(
                WsMessageDTO.MessageType.SESSION_STATUS,
                sessionStatus
        );
        
        int sessionCount = sessions.size();
        log.debug("Broadcasting session status to {} active WebSocket sessions", sessionCount);
        broadcastMessage(message);
        
        // If we just got a session, send initial state to all clients
        if (authService.hasActiveSession()) {
            for (WebSocketSession session : sessions) {
                try {
                    log.debug("Sending initial state to session {} after session update", session.getId());
                    sendInitialState(session);
                } catch (Exception e) {
                    log.error("Error sending initial state after session update to session {}: {}", 
                             session.getId(), e.getMessage(), e);
                }
            }
        }
    }

    private void sendInitialState(WebSocketSession session) {
        log.debug("Sending initial state snapshot to session: {}", session.getId());
        AppConfig config = configLoader.getConfig();
        if (config == null || config.getUsers() == null) {
            log.warn("Cannot send initial state: Config not loaded or no users.");
            return;
        }

        Map<String, UserConfig> configMap = config.getUsers().stream()
            .collect(Collectors.toMap(UserConfig::getVrcUid, uc -> uc));

        List<StatusUpdateDTO> initialStatePayload = config.getUsers().stream()
                .map(userConfig -> {
                    UserStateService.UserState currentState = userStateService.getLatestUserState(userConfig.getVrcUid());
                    if (currentState != null) {
                        return new StatusUpdateDTO(
                            userConfig.getVrcUid(),
                            userConfig.getHrToken(),
                            currentState.user(),
                            currentState.statusType(),
                            currentState.errorMessage(),
                            currentState.lastUpdated(),
                            userConfig.getAnnounceVolumeMult()
                        );
                    } else {
                        // When not logged in or no data yet, show as disconnected
                        return new StatusUpdateDTO(
                            userConfig.getVrcUid(),
                            userConfig.getHrToken(),
                            null, 
                            authService.hasActiveSession() ? UserStateService.StatusType.ERROR : UserStateService.StatusType.OFFLINE, 
                            authService.hasActiveSession() ? "Initializing..." : "Server not connected", 
                            Instant.now(),
                            userConfig.getAnnounceVolumeMult()
                        );
                    }
                })
                .collect(Collectors.toList());

        // Include server start time in the message
        Map<String, Object> metadata = Map.of(
            "serverStartTime", VrcMonitorApplication.getServerStartTime().toEpochMilli()
        );
        
        WsMessageDTO message = new WsMessageDTO(
            WsMessageDTO.MessageType.INITIAL_STATE, 
            initialStatePayload,
            metadata
        );
        
        sendMessage(session, message);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("Received WebSocket message from {}: {}", session.getId(), payload);
        
        // Check if it's a JSON message with a command
        if (payload.startsWith("{")) {
            try {
                Map<String, Object> commandMap = objectMapper.readValue(payload, Map.class);
                if ("COMMAND".equals(commandMap.get("type")) && "SHUTDOWN".equals(commandMap.get("command"))) {
                    handleShutdownCommand(session);
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to parse JSON command: {}", e.getMessage());
                // Continue with regular message processing
            }
        }
        
        // Handle regular text messages
        if ("REFRESH".equalsIgnoreCase(payload)) {
             log.info("Processing REFRESH request from session: {}", session.getId());
             sendInitialState(session); // Resend current state
        }
    }
    
    private void handleShutdownCommand(WebSocketSession session) {
        log.warn("SHUTDOWN command received from session: {}", session.getId());
        
        // Notify all clients about the shutdown
        WsMessageDTO shutdownMessage = new WsMessageDTO(
            WsMessageDTO.MessageType.SYSTEM, 
            Map.of(
                "action", "SHUTDOWN",
                "message", "Server is shutting down by administrator request."
            )
        );
        
        broadcastMessage(shutdownMessage);
        
        // Close all WebSocket sessions gracefully
        for (WebSocketSession ws : sessions) {
            try {
                ws.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.error("Error closing WebSocket session during shutdown: {}", e.getMessage());
            }
        }
        
        // Schedule actual application shutdown
        log.warn("Initiating server shutdown sequence...");
        
        // Use a separate thread to allow current request to complete
        new Thread(() -> {
            try {
                // Give clients time to receive the close message
                Thread.sleep(1000);
                
                log.warn("Executing shutdown...");
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Shutdown sequence interrupted", e);
            }
        }, "ShutdownThread").start();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage(), exception);
        sessions.remove(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket connection closed: SessionId={}, Status={}", session.getId(), status);
    }

    // Method for MonitoringService to call
    public void broadcastStatusUpdate(UserStateService.UserState userState) {
        if (userState == null) return;
        
        AppConfig config = configLoader.getConfig();
        UserConfig userConfig = config.getUsers().stream()
            .filter(u -> u.getVrcUid().equals(userState.user() != null ? userState.user().getId() : null))
            .findFirst()
            .orElse(null);

        String hrToken = (userConfig != null) ? userConfig.getHrToken() : "UNKNOWN_TOKEN";
        Double volumeMult = (userConfig != null) ? userConfig.getAnnounceVolumeMult() : null;

        String vrcUid = userState.user() != null ? userState.user().getId() : null;
        if (vrcUid == null && userConfig != null) {
             vrcUid = userConfig.getVrcUid();
        } else if (vrcUid == null) {
             log.warn("Cannot determine vrcUid for broadcast. User object is null and UserConfig not found.");
             return;
        }

        StatusUpdateDTO payload = new StatusUpdateDTO(
            vrcUid,
            hrToken,
            userState.user(),
            userState.statusType(),
            userState.errorMessage(),
            userState.lastUpdated(),
            volumeMult
        );

        WsMessageDTO message = new WsMessageDTO(WsMessageDTO.MessageType.USER_UPDATE, payload);
        broadcastMessage(message);
    }

    /**
     * Broadcasts a log entry to all connected WebSocket clients
     * @param logEntry The log entry to broadcast
     */
    public void broadcastLogEntry(LogEntryDTO logEntry) {
        if (logEntry == null) {
            log.debug("Ignoring null log entry");
            return;
        }
        
        // Skip broadcasting if no active sessions to reduce unnecessary processing
        if (sessions.isEmpty()) {
            log.debug("No active sessions, skipping log broadcast");
            return;
        }
        
        log.debug("Broadcasting log entry: type={}, timestamp={}", logEntry.getType(), logEntry.getTimestamp());
        
        try {
            WsMessageDTO message = new WsMessageDTO(WsMessageDTO.MessageType.LOG_ENTRY, logEntry);
            
            // Use try-catch to prevent logging errors from affecting the application
            try {
                broadcastMessage(message);
            } catch (Exception e) {
                // Don't let WebSocket issues crash the application
                log.error("Error broadcasting log entry (WebSocket error): {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Error preparing log entry for broadcast: {}", e.getMessage(), e);
        }
    }

    private void broadcastMessage(WsMessageDTO message) {
        String messageJson = convertToJson(message);
        if (messageJson == null) return;

        TextMessage textMessage = new TextMessage(messageJson);
        log.debug("Broadcasting WebSocket message to {} sessions: {}", sessions.size(), messageJson);
        
        // Synchronize to prevent concurrent sends to the same session
        synchronized (broadcastLock) {
            for (WebSocketSession session : sessions) {
                try {
                    sendMessage(session, textMessage);
                } catch (Exception e) {
                    log.warn("Error sending broadcast message to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    private void sendMessage(WebSocketSession session, WsMessageDTO message) {
         String messageJson = convertToJson(message);
         if (messageJson == null) return;
         sendMessage(session, new TextMessage(messageJson));
    }

    private void sendMessage(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to session {}: {}", session.getId(), e.getMessage(), e);
            // Consider removing session if send fails repeatedly
            // sessions.remove(session);
        }
    }

    private String convertToJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON for WebSocket: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Broadcasts a client API request to all connected WebSocket clients
     * @param requestDetails The details of the request
     */
    public void broadcastClientRequest(String requestDetails) {
        if (sessions.isEmpty()) {
            log.debug("No active sessions, skipping client request broadcast");
            return;
        }
        
        try {
            LogEntryDTO logEntry = new LogEntryDTO(
                "client-request",
                requestDetails,
                Instant.now()
            );
            
            broadcastLogEntry(logEntry);
        } catch (Exception e) {
            log.warn("Error broadcasting client request: {}", e.getMessage());
        }
    }
} 