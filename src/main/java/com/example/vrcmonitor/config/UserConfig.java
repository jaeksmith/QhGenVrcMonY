package com.example.vrcmonitor.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.time.DurationFormatUtils; // Using Commons Lang for parsing
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration for each user to be monitored.
 * Each user should have a reasonable poll rate to avoid
 * overwhelming the VRChat API with too many requests.
 */
@Data // Lombok annotation for getters, setters, toString, etc.
@NoArgsConstructor // Needed for Jackson deserialization
public class UserConfig {

    private static final Logger log = LoggerFactory.getLogger(UserConfig.class);

    private String hrToken;
    private String vrcUid;
    
    /**
     * The poll rate defines how frequently we check this user's status.
     * IMPORTANT: To avoid VRChat API rate limits, this should be set to
     * a reasonable value (10+ minutes recommended). Format examples:
     * "15m", "1h30m", "10m30s", etc.
     */
    private String pollRate; // Store the raw string
    
    private Double announceVolumeMult; // Add optional volume multiplier (nullable Double)

    @JsonIgnore // Don't serialize/deserialize this derived field directly
    public Duration getPollRateDuration() {
        return parsePollRate(this.pollRate);
    }

    // Simple parser for "XmYs" format
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?", Pattern.CASE_INSENSITIVE);

    /**
     * Parse the poll rate string into a Duration object.
     * If the poll rate is invalid or too short, we default to 1 minute
     * to ensure a minimum delay between API calls.
     */
    private Duration parsePollRate(String rate) {
        if (rate == null || rate.isBlank()) {
            log.warn("Poll rate is null or blank for user {}. Defaulting to 1 minute.", hrToken != null ? hrToken : vrcUid);
            return Duration.ofMinutes(1);
        }
        long seconds = 0;
        Matcher matcher = DURATION_PATTERN.matcher(rate.trim());
        if (matcher.matches()) {
            if (matcher.group(1) != null) seconds += Long.parseLong(matcher.group(1)) * 86400; // days
            if (matcher.group(2) != null) seconds += Long.parseLong(matcher.group(2)) * 3600;  // hours
            if (matcher.group(3) != null) seconds += Long.parseLong(matcher.group(3)) * 60;    // minutes
            if (matcher.group(4) != null) seconds += Long.parseLong(matcher.group(4));       // seconds
        }

        if (seconds <= 0) {
             log.warn("Invalid or zero poll rate '{}' for user {}. Defaulting to 1 minute.", rate, hrToken != null ? hrToken : vrcUid);
             return Duration.ofMinutes(1);
        }
        return Duration.ofSeconds(seconds);
    }

     @Override
    public String toString() {
        // Include new field if not null
        String base = "UserConfig{hrToken='" + hrToken + "', vrcUid='" + vrcUid + "', pollRate='" + pollRate + "'";
        if (announceVolumeMult != null) {
            base += ", announceVolumeMult=" + announceVolumeMult;
        }
        return base + "}";
    }
} 