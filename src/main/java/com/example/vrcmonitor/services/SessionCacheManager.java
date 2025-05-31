package com.example.vrcmonitor.services;

import com.example.vrcmonitor.config.ConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the persistence of VRChat session information.
 * 
 * This service handles saving and loading session cookies to/from a file
 * to enable session persistence between application restarts.
 * 
 * No login credentials are stored, only the session cookies required
 * to maintain an authenticated session with the VRChat API.
 */
@Service
public class SessionCacheManager {
    private static final Logger log = LoggerFactory.getLogger(SessionCacheManager.class);
    private static final String SESSION_CACHE_FILENAME = "vrc_session_cache.json";
    
    private final ConfigLoader configLoader;
    private final ObjectMapper objectMapper;
    
    public SessionCacheManager(ConfigLoader configLoader, ObjectMapper objectMapper) {
        this.configLoader = configLoader;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Saves the session cookies to a file if configured to do so.
     * 
     * @param authCookie The main auth cookie
     * @param twoFactorAuthCookie The two-factor auth cookie (may be null)
     * @return true if the cache was successfully saved, false otherwise
     */
    public boolean saveSessionCache(String authCookie, String twoFactorAuthCookie) {
        if (!configLoader.getConfig().getFileCacheSesssionInfo()) {
            log.debug("Session caching is disabled, not saving session cookies");
            return false;
        }
        
        if (authCookie == null) {
            log.warn("Cannot save session cache: auth cookie is null");
            return false;
        }
        
        try {
            Map<String, String> sessionData = new HashMap<>();
            sessionData.put("authCookie", authCookie);
            
            if (twoFactorAuthCookie != null) {
                sessionData.put("twoFactorAuthCookie", twoFactorAuthCookie);
            }
            
            objectMapper.writeValue(new File(SESSION_CACHE_FILENAME), sessionData);
            log.info("Session cache saved successfully to {}", SESSION_CACHE_FILENAME);
            return true;
        } catch (IOException e) {
            log.error("Failed to save session cache: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Loads session cookies from the cache file if available and configured.
     * 
     * @return A map containing the session cookies, or null if no cache exists or loading failed
     */
    public Map<String, String> loadSessionCache() {
        if (!configLoader.getConfig().getFileCacheSesssionInfo()) {
            log.debug("Session caching is disabled, not loading session cookies");
            return null;
        }
        
        File cacheFile = new File(SESSION_CACHE_FILENAME);
        if (!cacheFile.exists()) {
            log.debug("No session cache file found at {}", SESSION_CACHE_FILENAME);
            return null;
        }
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> sessionData = objectMapper.readValue(cacheFile, Map.class);
            log.info("Session cache loaded successfully from {}", SESSION_CACHE_FILENAME);
            return sessionData;
        } catch (IOException e) {
            log.error("Failed to load session cache: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Clears the session cache by deleting the cache file.
     * Should be called when the session is explicitly terminated or becomes invalid.
     * 
     * @return true if the cache was successfully cleared or didn't exist, false on error
     */
    public boolean clearSessionCache() {
        File cacheFile = new File(SESSION_CACHE_FILENAME);
        if (!cacheFile.exists()) {
            log.debug("No session cache file to clear");
            return true;
        }
        
        try {
            Files.delete(Paths.get(SESSION_CACHE_FILENAME));
            log.info("Session cache cleared successfully");
            return true;
        } catch (IOException e) {
            log.error("Failed to clear session cache: {}", e.getMessage(), e);
            return false;
        }
    }
} 