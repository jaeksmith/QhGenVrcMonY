package com.example.vrcmonitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct; // Use jakarta annotation with newer Spring Boot
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@Service
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String CONFIG_FILENAME = "config.json";

    @Value("classpath:config.json") // Fallback to classpath
    private Resource classPathConfigResource;

    private AppConfig appConfig;
    // Inject the primary ObjectMapper bean configured in JacksonConfig
    private final ObjectMapper objectMapper;

    public ConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct // Load config when the service is created
    public void loadConfig() throws IOException {
        // First try to load from current working directory
        File externalConfigFile = new File(CONFIG_FILENAME);
        Resource configFileResource;
        
        if (externalConfigFile.exists() && externalConfigFile.isFile()) {
            configFileResource = new FileSystemResource(externalConfigFile);
            log.info("Found external configuration file: {}", externalConfigFile.getAbsolutePath());
        } else {
            configFileResource = classPathConfigResource;
            
            // Display more visible warning messages when using the default config
            log.warn("********************************************************************");
            log.warn("*                         WARNING                                   *");
            log.warn("* External configuration file not found at: {}",                     
                    new File(CONFIG_FILENAME).getAbsolutePath());
            log.warn("* Using BUILT-IN DEFAULT configuration. This is not recommended     *");
            log.warn("* for production use.                                              *");
            log.warn("*                                                                  *");
            log.warn("* Please create a '{}' file in the same directory as the          *", 
                    CONFIG_FILENAME);
            log.warn("* application to customize your configuration.                      *");
            log.warn("********************************************************************");
            
            System.out.println("\n!!! WARNING: Using default built-in configuration !!!");
            System.out.println("Create a config.json file in the application directory for customization.\n");
        }
        
        log.info("Attempting to load configuration from {}", configFileResource.getDescription());
        try (InputStream inputStream = configFileResource.getInputStream()) {
            appConfig = objectMapper.readValue(inputStream, AppConfig.class);
            int userCount = (appConfig != null && appConfig.getUsers() != null) ? appConfig.getUsers().size() : 0;
            log.info("Configuration loaded successfully: {} users.", userCount);
             // Validate poll rates during load
            if (userCount > 0) {
                appConfig.getUsers().forEach(user -> {
                    Duration d = user.getPollRateDuration(); // Trigger parsing and potential warnings
                    log.debug(" - Parsed config for {}: Poll rate = {}", 
                              user.getHrToken(), 
                              DurationFormatUtils.formatDurationWords(d.toMillis(), true, true));
                });
            }
        } catch (IOException e) {
            log.error("FATAL: Could not load or parse {}. Please ensure it exists and is valid JSON.", configFileResource.getFilename(), e);
            throw e; // Re-throw to prevent application startup
        }
    }

    public AppConfig getConfig() {
        if (appConfig == null) {
            // This shouldn't happen if PostConstruct logic is correct, but good safeguard
            throw new IllegalStateException("Configuration has not been loaded yet.");
        }
        return appConfig;
    }
} 