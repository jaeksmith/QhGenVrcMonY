package com.example.vrcmonitor;

import com.example.vrcmonitor.config.ConfigLoader;
import com.example.vrcmonitor.services.AuthService;
import com.example.vrcmonitor.services.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;

@SpringBootApplication
@EnableScheduling
public class VrcMonitorApplication {

    private static final Logger log = LoggerFactory.getLogger(VrcMonitorApplication.class);
    private static final Instant SERVER_START_TIME = Instant.now();
    
    public static Instant getServerStartTime() {
        return SERVER_START_TIME;
    }

    public static void main(String[] args) {
        // Disable headless mode to ensure Console is available if running in an environment without a display
         System.setProperty("java.awt.headless", "false");
        SpringApplication.run(VrcMonitorApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(AuthService authService, ConfigLoader configLoader, MonitoringService monitoringService, ConfigurableApplicationContext ctx) {
        return args -> {
            log.info("VRChat Monitor Starting...");

            // Load configuration without requiring login
            log.info("Loading monitoring configuration...");
            try {
                log.info("Monitoring Configuration:");
                configLoader.getConfig().getUsers().forEach(user -> log.info("- {}", user));
            } catch (Exception e) {
                log.error("Failed to load configuration: {}", e.getMessage(), e);
                ctx.close();
                System.exit(1);
            }

            // No longer perform console login - wait for client login
            log.info("Starting in disconnected mode. Waiting for client login...");
            
            // The monitoring service will check for active session before polling
            monitoringService.startMonitoring();

            log.info("Web UI will be available shortly on http://localhost:8080");
        };
    }
} 