package com.dreamteam.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Configuration for distributed cluster nodes.
 * Supports environment variables and properties file.
 */
public class ClusterConfig {

    private static final Properties properties = new Properties();

    static {
        try (InputStream input = ClusterConfig.class.getClassLoader()
                .getResourceAsStream("cluster.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load cluster.properties: " + e.getMessage());
        }
    }

    /**
     * Gets a configuration value, checking environment variables first, then properties file.
     */
    public static String get(String key, String defaultValue) {
        // Environment variable takes precedence (replace dots with underscores)
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // Then check system properties
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }

        // Finally check properties file
        return properties.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }

    public static List<String> getList(String key, String defaultValue) {
        String value = get(key, defaultValue);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ==================== Node Configuration ====================

    /** Unique identifier for this node in the cluster */
    public static String getNodeId() {
        return get("node.id", "node-1");
    }

    /** Port for this service instance */
    public static int getServerPort(int defaultPort) {
        return getInt("server.port", defaultPort);
    }

    // ==================== Datalake Configuration ====================

    /** Base directory for local datalake partition */
    public static String getDatalakeDir() {
        return get("datalake.dir", "./datalake");
    }

    /** Replication factor for datalake (R = number of copies) */
    public static int getReplicationFactor() {
        return getInt("datalake.replication.factor", 2);
    }

    /** List of peer node URLs for datalake replication */
    public static List<String> getDatalakePeers() {
        return getList("datalake.peers", "");
    }

    // ==================== Message Broker Configuration ====================

    /** ActiveMQ broker URL */
    public static String getBrokerUrl() {
        return get("broker.url", "tcp://localhost:61616");
    }

    /** Queue/Topic name for indexing requests */
    public static String getIndexingQueue() {
        return get("broker.queue.indexing", "indexing-requests");
    }

    /** Broker username (optional) */
    public static String getBrokerUsername() {
        return get("broker.username", null);
    }

    /** Broker password (optional) */
    public static String getBrokerPassword() {
        return get("broker.password", null);
    }

    // ==================== Hazelcast Configuration ====================

    /** Hazelcast cluster name */
    public static String getHazelcastClusterName() {
        return get("hazelcast.cluster.name", "stage3-cluster");
    }

    /** Hazelcast member addresses for TCP/IP discovery */
    public static List<String> getHazelcastMembers() {
        return getList("hazelcast.members", "127.0.0.1:5701");
    }

    /** Hazelcast port for this node */
    public static int getHazelcastPort() {
        return getInt("hazelcast.port", 5701);
    }

    /** Number of backup copies for Hazelcast maps */
    public static int getHazelcastBackupCount() {
        return getInt("hazelcast.backup.count", 1);
    }

    // ==================== Index Configuration ====================

    /** Name of the distributed inverted index map */
    public static String getIndexMapName() {
        return get("index.map.name", "inverted-index");
    }

    // ==================== Search Service Configuration ====================

    /** Database path for metadata */
    public static String getDbPath() {
        return get("db.path", "datamart/datamart.db");
    }
}
