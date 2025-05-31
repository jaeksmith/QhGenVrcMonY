package com.example.vrcmonitor.web;

import com.example.vrcmonitor.VrcMonitorApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);
    
    // Inject build properties from Maven
    @Value("${application.version:unknown}")
    private String applicationVersion;
    
    @Value("${application.buildTime:#{null}}")
    private String buildTime;
    
    @GetMapping("/build-info")
    public ResponseEntity<Map<String, String>> getBuildInfo() {
        log.debug("Getting build information");
        
        Map<String, String> buildInfo = new HashMap<>();
        
        // Application version from Maven properties
        buildInfo.put("version", applicationVersion);
        
        // Build timestamp - either from Maven or fallback to application start time
        if (buildTime != null && !buildTime.isEmpty() && !"${maven.build.timestamp}".equals(buildTime)) {
            buildInfo.put("buildTime", buildTime);
        } else {
            // Fallback to server start time as a proxy for build time
            Instant startTime = VrcMonitorApplication.getServerStartTime();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            buildInfo.put("buildTime", formatter.format(startTime) + " (server start time)");
        }
        
        log.debug("Returning build info: {}", buildInfo);
        return ResponseEntity.ok(buildInfo);
    }
} 