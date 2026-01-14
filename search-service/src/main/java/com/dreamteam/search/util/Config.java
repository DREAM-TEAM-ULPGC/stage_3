package com.dreamteam.search.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

/**
 * Configuration utility for search service.
 * Supports environment variable overrides for horizontal scalability.
 * Environment variables take precedence over properties file.
 */
public class Config {
    private static final Properties properties = new Properties();
    private static final String NODE_ID = generateNodeId();

    static {
        try (InputStream input = Config.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new RuntimeException("Unable to find application.properties");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new RuntimeException("Error loading application.properties", exception);
        }
    }

    private static String generateNodeId() {
        String envNodeId = System.getenv("NODE_ID");
        if (envNodeId != null && !envNodeId.isBlank()) {
            return envNodeId;
        }
        return "search-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String getNodeId() {
        return NODE_ID;
    }

    /**
     * Gets a property value with environment variable override.
     * Converts property key to ENV format: server.port -> SERVER_PORT
     */
    public static String getEnvOrDefault(String key, String def) {
        // First check environment variable (convert dot notation to underscore uppercase)
        String envKey = key.replace(".", "_").toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return properties.getProperty(key, def);
    }

    public static int getIntProperty(String key, int defaultValue) {
        String value = getEnvOrDefault(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getEnvOrDefault(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
