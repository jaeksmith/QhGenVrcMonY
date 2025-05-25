package com.example.vrcmonitor.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final StatusUpdateHandler statusUpdateHandler;

    public WebSocketConfig(StatusUpdateHandler statusUpdateHandler) {
        this.statusUpdateHandler = statusUpdateHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Map the endpoint /ws/status to our handler
        // Allow all origins for simplicity during development (adjust for production)
        registry.addHandler(statusUpdateHandler, "/ws/status").setAllowedOrigins("*");
    }

    // Optional: Configure buffer sizes, idle timeouts etc. if needed
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // container.setMaxTextMessageBufferSize(8192);
        // container.setMaxBinaryMessageBufferSize(8192);
        // container.setMaxSessionIdleTimeout(300000L); // 5 minutes
        return container;
    }
} 