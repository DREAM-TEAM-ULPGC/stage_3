package com.dreamteam.ingestion.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {
	private static final Properties props = new Properties();
	static {
		try (InputStream is = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
			if (is != null) props.load(is);
		} catch (IOException ignored) {}
	}

	public static int port() {
		String env = System.getenv("PORT");
		if (env != null) return Integer.parseInt(env);
		return Integer.parseInt(props.getProperty("server.port", "7001"));
	}
	public static String datalakeDir() {
		String env = System.getenv("DATALAKE_DIR");
		if (env != null && !env.isBlank()) return env;
		return props.getProperty("datalake.dir", "./datalake");
	}
	public static String ingestionLogFile() {
		String env = System.getenv("INGESTION_LOG_FILE");
		if (env != null && !env.isBlank()) return env;
		return props.getProperty("ingestion.log.file", "./datalake/ingestions.log");
	}
}
