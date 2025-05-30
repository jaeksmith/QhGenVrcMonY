package com.example.vrcmonitor.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // Ignore fields not defined here
public class VRChatUser {
    private String id;
    private String username;
    private String displayName;
    private String state;
    private String status;
    private String statusDescription;
    private String location;
    private String worldId; // Often includes instance ID
    private String instanceId;
    private String currentAvatarImageUrl;
    private String currentAvatarThumbnailImageUrl;
    private Instant last_login;
    private boolean isFriend;
    private String bio;
    private String userIcon;
    // Add more fields as needed based on VRChat API documentation
    
    /**
     * Gets the state with null-safety
     * @return The state or "unknown" if null
     */
    public String getState() {
        return state != null ? state : "unknown";
    }
    
    /**
     * Gets the status with null-safety
     * @return The status or "unknown" if null
     */
    public String getStatus() {
        return status != null ? status : "unknown";
    }
    
    /**
     * Gets the location with null-safety
     * @return The location or "unknown" if null
     */
    public String getLocation() {
        return location != null ? location : "unknown";
    }
} 