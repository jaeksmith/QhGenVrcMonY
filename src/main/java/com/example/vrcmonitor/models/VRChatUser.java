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
} 