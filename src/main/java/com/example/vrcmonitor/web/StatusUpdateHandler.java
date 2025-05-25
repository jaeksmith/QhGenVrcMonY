package com.example.vrcmonitor.web;

import com.example.vrcmonitor.config.AppConfig;
import com.example.vrcmonitor.config.ConfigLoader;
import com.example.vrcmonitor.config.UserConfig;
import com.example.vrcmonitor.models.dto.StatusUpdateDTO;
import com.example.vrcmonitor.models.dto.WsMessageDTO;
import com.example.vrcmonitor.services.UserStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public StatusUpdateHandler(UserStateService userStateService, ConfigLoader configLoader, ObjectMapper objectMapper) {
        this.userStateService = userStateService;
        this.configLoader = configLoader;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket connection established: SessionId={}, RemoteAddress={}", session.getId(), session.getRemoteAddress());
        sendInitialState(session);
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
                        return new StatusUpdateDTO(
                            userConfig.getVrcUid(),
                            userConfig.getHrToken(),
                            null, 
                            UserStateService.StatusType.ERROR, 
                            "Initializing...", 
                            Instant.now(),
                            userConfig.getAnnounceVolumeMult()
                        );
                    }
                })
                .collect(Collectors.toList());

        WsMessageDTO message = new WsMessageDTO(WsMessageDTO.MessageType.INITIAL_STATE, initialStatePayload);
        sendMessage(session, message);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("Received WebSocket message from {}: {}", session.getId(), payload);
        // Handle incoming messages (e.g., refresh request)
        // For now, just echoing back as an example
         // WsMessageDTO response = new WsMessageDTO(WsMessageDTO.MessageType.ECHO, "Received: " + payload);
         // sendMessage(session, response);
         // TODO: Implement actual message handling if needed (like refresh)
         if ("REFRESH".equalsIgnoreCase(payload)) {
              log.info("Processing REFRESH request from session: {}", session.getId());
              sendInitialState(session); // Resend current state
         }
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

    private void broadcastMessage(WsMessageDTO message) {
        String messageJson = convertToJson(message);
        if (messageJson == null) return;

        TextMessage textMessage = new TextMessage(messageJson);
        log.debug("Broadcasting WebSocket message to {} sessions: {}", sessions.size(), messageJson);
        sessions.forEach(session -> sendMessage(session, textMessage));
    }

    private void sendMessage(WebSocketSession session, WsMessageDTO message) {
         String messageJson = convertToJson(message);
         if (messageJson == null) return;
         sendMessage(session, new TextMessage(messageJson));
    }

    private void sendMessage(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
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
} 