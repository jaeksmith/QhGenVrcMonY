package com.example.vrcmonitor.models.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * DTO for sending log entries to the client
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEntryDTO {
    private String type;    // "request" or "response"
    private String content; // The log content (already sanitized)
    private Instant timestamp;
} 