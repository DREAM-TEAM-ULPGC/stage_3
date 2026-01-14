package com.dreamteam.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

/**
 * Configuration loader for indexer service.
 * Supports environment variable overrides for horizontal scalability.
 * Environment variables take precedence over properties file.
 */
public class ConfigLoader {
    private static final Properties properties = new Properties();
    private static final String NODE_ID = generateNodeId();

    static {
        try (InputStream input = ConfigLoader.class.getClassLoader()
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
        // Check properties file
        String propNodeId = properties.getProperty("node.id");
        if (propNodeId != null && !propNodeId.isBlank()) {
            return propNodeId;
        }
        return "indexer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String getNodeId() {
        return NODE_ID;
    }

    /**
     * Gets a property value with environment variable override.
     * Converts property key to ENV format: server.port -> SERVER_PORT
     */
    public static String getProperty(String key, String defaultValue) {
        // First check environment variable (convert dot notation to underscore uppercase)
        String envKey = key.replace(".", "_").toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    public static String getProperty(String key) {
        return getProperty(key, null);
    }

    public static int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key, null);
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
        String value = getProperty(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
