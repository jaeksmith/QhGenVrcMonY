package com.example.vrcmonitor.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Main application configuration class.
 * Contains global settings and the list of users to monitor.
 */
@Data
@NoArgsConstructor
public class AppConfig {
    private List<UserConfig> users;
    
    /**
     * When true, errors will be logged to a file in the logs directory.
     * Default is false.
     */
    private Boolean logErrorsToFile = false; // Default to false if not specified in config
    
    /**
     * When true, session information (auth cookies) will be cached to a file
     * to allow session persistence between application restarts.
     * This does NOT store login credentials, only session cookies.
     * Default is false.
     */
    private Boolean fileCacheSesssionInfo = false; // Default to false if not specified in config
} 