package com.dreamteam.ingestion.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

/**
 * Configuration for ingestion service.
 * Supports environment variable overrides for horizontal scalability.
 */
public class Config {
	private static final Properties props = new Properties();
	private static final String NODE_ID = generateNodeId();
	
	static {
		try (InputStream is = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
			if (is != null) props.load(is);
		} catch (IOException ignored) {}
	}

	private static String generateNodeId() {
		String envNodeId = System.getenv("NODE_ID");
		if (envNodeId != null && !envNodeId.isBlank()) {
			return envNodeId;
		}
		return "ingestion-" + UUID.randomUUID().toString().substring(0, 8);
	}

	public static String getNodeId() {
		return NODE_ID;
	}

	/**
	 * Gets a property value with environment variable override.
	 */
	private static String getEnvOrProp(String envKey, String propKey, String defaultValue) {
		String envValue = System.getenv(envKey);
		if (envValue != null && !envValue.isBlank()) {
			return envValue;
		}
		return props.getProperty(propKey, defaultValue);
	}

	public static int port() {
		String value = getEnvOrProp("PORT", "server.port", "7001");
		return Integer.parseInt(value);
	}
	
	public static String datalakeDir() {
		return getEnvOrProp("DATALAKE_DIR", "datalake.dir", "./datalake");
	}
	
	public static String ingestionLogFile() {
		return getEnvOrProp("INGESTION_LOG_FILE", "ingestion.log.file", "./datalake/ingestions.log");
	}

	public static boolean brokerEnabled() {
		String value = getEnvOrProp("BROKER_ENABLED", "broker.enabled", "true");
		return Boolean.parseBoolean(value);
	}

	public static String brokerUrl() {
		return getEnvOrProp("BROKER_URL", "broker.url", "tcp://localhost:61616");
	}

	public static String brokerTopic() {
		return getEnvOrProp("BROKER_TOPIC", "broker.topic.ingestion", "ingestion-events");
	}
}
