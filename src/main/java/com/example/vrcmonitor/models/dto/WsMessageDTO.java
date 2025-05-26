package com.example.vrcmonitor.models.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsMessageDTO {
    private MessageType type;
    private Object payload; // Can hold different DTOs based on type

    public enum MessageType {
        INITIAL_STATE, // For sending snapshot on connect
        USER_UPDATE,   // For broadcasting a single user change
        ERROR,         // For sending general backend errors
        CLIENT_REQUEST, // Placeholder for potential client->server messages
        SYSTEM        // For system-level messages like shutdown notifications
    }
} 