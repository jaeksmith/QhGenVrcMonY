package com.example.vrcmonitor.models.dto;

import com.example.vrcmonitor.services.VRChatApiService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultDTO {
    private boolean success;
    private boolean requires2FA;
    private String twoFactorType; // "totp" or "emailOtp"
    private String message;
    private String resultCode; // From LoginResult enum
    
    // Convenience constructor for success
    public static LoginResultDTO success() {
        return new LoginResultDTO(true, false, null, "Login successful", VRChatApiService.LoginResult.SUCCESS.name());
    }
    
    // Convenience constructor for 2FA required
    public static LoginResultDTO requires2FA(String twoFactorType) {
        return new LoginResultDTO(false, true, twoFactorType, "Two-factor authentication required", null);
    }
    
    // Convenience constructor for failure
    public static LoginResultDTO failure(VRChatApiService.LoginResult result) {
        return new LoginResultDTO(false, false, null, result.name(), result.name());
    }
} 