package com.example.vrcmonitor.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AppConfig {
    private List<UserConfig> users;
    private Boolean logErrorsToFile = false; // Default to false if not specified in config
} 