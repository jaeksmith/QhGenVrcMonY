package com.example.vrcmonitor.models.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsMessageDTO {
    private MessageType type;
    private Object payload; // Can hold different DTOs based on type
    private Map<String, Object> metadata; // For additional data like server start time
    
    public WsMessageDTO(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
        this.metadata = null;
    }

    public enum MessageType {
        INITIAL_STATE, // For sending snapshot on connect
        USER_UPDATE,   // For broadcasting a single user change
        ERROR,         // For sending general backend errors
        CLIENT_REQUEST, // Placeholder for potential client->server messages
        SYSTEM,        // For system-level messages like shutdown notifications
        LOG_ENTRY      // For sending API log entries to clients
    }
} 