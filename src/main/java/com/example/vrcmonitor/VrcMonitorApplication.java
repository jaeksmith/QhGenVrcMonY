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

@SpringBootApplication
@EnableScheduling
public class VrcMonitorApplication {

    private static final Logger log = LoggerFactory.getLogger(VrcMonitorApplication.class);

    public static void main(String[] args) {
        // Disable headless mode to ensure Console is available if running in an environment without a display
         System.setProperty("java.awt.headless", "false");
        SpringApplication.run(VrcMonitorApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(AuthService authService, ConfigLoader configLoader, MonitoringService monitoringService, ConfigurableApplicationContext ctx) {
        return args -> {
            log.info("VRChat Monitor Starting...");

            // Perform initial authentication before starting web server etc.
            boolean authenticated = authService.performConsoleLogin();

            if (!authenticated) {
                log.error("Authentication failed after multiple attempts. Exiting.");
                // Shutdown the application context gracefully
                ctx.close();
                System.exit(1); // Exit
            } else {
                 log.info("Authentication successful.");
                 log.info("Proceeding with application startup...");
                 // At this point, Spring Boot continues starting other components (web server, etc.)

                 log.info("Monitoring Configuration:");
                 configLoader.getConfig().getUsers().forEach(user -> log.info("- {}", user));

                 // Start the monitoring service now that authentication is successful
                 monitoringService.startMonitoring();

                 log.info("Web UI will be available shortly on http://localhost:8080 (further implementation needed).");
                 // Placeholder for where monitoring would start
            }
        };
    }
} 