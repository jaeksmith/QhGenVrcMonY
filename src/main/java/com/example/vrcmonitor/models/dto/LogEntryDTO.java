package com.example.vrcmonitor.models.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    
    @JsonIgnore // Don't include the Instant object directly in JSON
    private Instant timestamp;
    
    @JsonProperty("timestamp")
    public long getTimestampMillis() {
        return timestamp != null ? timestamp.toEpochMilli() : System.currentTimeMillis();
    }
} 