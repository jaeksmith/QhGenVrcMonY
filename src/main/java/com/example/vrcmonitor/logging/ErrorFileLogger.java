package com.example.vrcmonitor.logging;

import com.example.vrcmonitor.config.ConfigLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Component that logs errors to daily rolling files when enabled in config.
 * Also handles cleanup of log files older than 3 days.
 */
@Component
public class ErrorFileLogger {
    private static final Logger log = LoggerFactory.getLogger(ErrorFileLogger.class);
    private static final String LOG_DIRECTORY = "logs";
    private static final String LOG_FILE_PREFIX = "vrc-monitor-errors-";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int LOG_RETENTION_DAYS = 3;
    
    private final ConfigLoader configLoader;
    private final ReentrantLock fileLock = new ReentrantLock();
    
    public ErrorFileLogger(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
    
    @PostConstruct
    public void init() {
        // Create logs directory if it doesn't exist
        try {
            Files.createDirectories(Paths.get(LOG_DIRECTORY));
            
            // Create .gitignore in logs directory to exclude log files from git
            Path gitignorePath = Paths.get(LOG_DIRECTORY, ".gitignore");
            if (!Files.exists(gitignorePath)) {
                Files.writeString(gitignorePath, "*.log\n");
                log.info("Created .gitignore in logs directory");
            }
            
            log.info("Error file logging initialized. Log directory: {}", Paths.get(LOG_DIRECTORY).toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create logs directory: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Logs an error message to the current day's log file if error logging is enabled
     * 
     * @param message The error message to log
     * @param throwable Optional throwable to include stack trace
     */
    public void logError(String message, Throwable throwable) {
        if (!isLoggingEnabled()) {
            return;
        }
        
        String currentDate = LocalDate.now().format(DATE_FORMATTER);
        String logFilePath = LOG_DIRECTORY + File.separator + LOG_FILE_PREFIX + currentDate + LOG_FILE_EXTENSION;
        
        fileLock.lock();
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFilePath, true))) {
            writer.println("[" + java.time.LocalDateTime.now() + "] ERROR: " + message);
            if (throwable != null) {
                writer.println("Exception: " + throwable.getClass().getName() + ": " + throwable.getMessage());
                throwable.printStackTrace(writer);
                writer.println();
            }
        } catch (IOException e) {
            log.error("Failed to write to error log file: {}", e.getMessage(), e);
        } finally {
            fileLock.unlock();
        }
    }
    
    /**
     * Logs an API error to the current day's log file
     * 
     * @param requestInfo Information about the request
     * @param statusCode The HTTP status code
     * @param responseBody The response body
     */
    public void logApiError(String requestInfo, int statusCode, String responseBody) {
        if (!isLoggingEnabled()) {
            return;
        }
        
        StringBuilder message = new StringBuilder();
        message.append("API Error: ").append(requestInfo).append("\n");
        message.append("Status Code: ").append(statusCode).append("\n");
        message.append("Response: ").append(responseBody);
        
        logError(message.toString(), null);
    }
    
    /**
     * Checks if error logging to file is enabled
     * 
     * @return true if enabled in config, false otherwise
     */
    public boolean isLoggingEnabled() {
        try {
            return configLoader.getConfig().getLogErrorsToFile() != null && 
                   configLoader.getConfig().getLogErrorsToFile();
        } catch (Exception e) {
            log.warn("Failed to check if error logging is enabled: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Scheduled task that runs daily at midnight to clean up old log files
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void cleanupOldLogFiles() {
        log.info("Running scheduled cleanup of old log files");
        LocalDate cutoffDate = LocalDate.now().minus(LOG_RETENTION_DAYS, ChronoUnit.DAYS);
        
        File logsDir = new File(LOG_DIRECTORY);
        File[] logFiles = logsDir.listFiles((dir, name) -> 
            name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION));
        
        if (logFiles == null) {
            log.warn("Failed to list log files for cleanup");
            return;
        }
        
        Arrays.stream(logFiles)
            .filter(file -> {
                try {
                    String datePart = file.getName()
                        .replace(LOG_FILE_PREFIX, "")
                        .replace(LOG_FILE_EXTENSION, "");
                    LocalDate fileDate = LocalDate.parse(datePart, DATE_FORMATTER);
                    return fileDate.isBefore(cutoffDate);
                } catch (Exception e) {
                    log.warn("Failed to parse date from filename: {}", file.getName());
                    return false;
                }
            })
            .forEach(file -> {
                if (file.delete()) {
                    log.info("Deleted old log file: {}", file.getName());
                } else {
                    log.warn("Failed to delete old log file: {}", file.getName());
                }
            });
    }
} 