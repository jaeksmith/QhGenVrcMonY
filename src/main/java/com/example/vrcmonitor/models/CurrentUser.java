package com.example.vrcmonitor.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.Instant;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentUser {
    private String id;
    private String username;
    private String displayName;
    private String bio;
    private List<String> bioLinks;
    private String userIcon;
    private String profilePicOverride;
    private String status;
    private String statusDescription;
    private String state;
    private List<String> tags;
    private String developerType;
    private Instant last_login;
    private String last_platform;
    private boolean allowAvatarCopying;
    private boolean isFriend;
    private String friendKey;
    // Field to indicate 2FA requirement (often a list of strings like ["emailOtp", "totp"])
    private List<String> requiresTwoFactorAuth;
    private String twoFactorAuthToken;
    // Add other relevant fields as needed
} 