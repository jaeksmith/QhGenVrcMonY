package com.example.vrcmonitor.models.dto;

import com.example.vrcmonitor.models.VRChatUser;
import com.example.vrcmonitor.services.UserStateService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusUpdateDTO {
    private String vrcUid;
    private String hrToken; // Include for easier mapping on client
    private VRChatUser user; // Full user object for details
    private UserStateService.StatusType statusType;
    private String errorMessage;
    private Instant lastUpdated;
    private Double announceVolumeMult; // Add volume multiplier
    // We might add specific fields like state, status string directly later for optimization
} 