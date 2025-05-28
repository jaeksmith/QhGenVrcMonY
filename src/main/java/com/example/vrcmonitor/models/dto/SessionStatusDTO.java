package com.example.vrcmonitor.models.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionStatusDTO {
    private boolean hasActiveSession;
    private Long lastSessionTimeMs;
    private String username;
    private String error;
    
    // Constructor with Instant
    public SessionStatusDTO(boolean hasActiveSession, Instant lastSessionTime, String username, String error) {
        this.hasActiveSession = hasActiveSession;
        this.lastSessionTimeMs = lastSessionTime != null ? lastSessionTime.toEpochMilli() : null;
        this.username = username;
        this.error = error;
    }
} 