package com.example.vrcmonitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary // Ensure this ObjectMapper is preferred
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Register the module for Java 8+ Time API (Instant, LocalDate, etc.)
        objectMapper.registerModule(new JavaTimeModule());
        // Optional: Configure date/time format (ISO-8601 is default for Instant with the module)
        // objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        System.out.println("DEBUG: Custom ObjectMapper bean created with JavaTimeModule.");
        return objectMapper;
    }
} 