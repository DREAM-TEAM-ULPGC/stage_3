package com.dreamteam.ingestion.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class BookDownloader {

	public static String downloadBookPlainText(int bookId) throws Exception {
		List<String> candidates = List.of(
				"https://www.gutenberg.org/cache/epub/%d/pg%d.txt",
				"https://www.gutenberg.org/cache/epub/%d/pg%d.txt.utf8",
				"https://www.gutenberg.org/files/%d/%d-0.txt",
				"https://www.gutenberg.org/files/%d/%d-0.txt.utf8",
				"https://www.gutenberg.org/files/%d/%d.txt"
		).stream().map(s -> s.formatted(bookId, bookId)).toList();

		Exception last = null;
		for (String url : candidates) {
			try {
				String s = httpGet(url, 20_000);
				if (s != null && s.length() > 0) return s;
			} catch (Exception exception) { last = exception; }
		}
		throw new Exception("Unable to download book " + bookId + " from Project Gutenberg", last);
	}
	private static String httpGet(String urlStr, int timeoutMs) throws Exception {
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setConnectTimeout(timeoutMs);
		connection.setReadTimeout(timeoutMs);
		connection.setRequestProperty("User-Agent", "DreamTeam-Ingestion/1.0");
		int code = connection.getResponseCode();
		if (code != 200) throw new RuntimeException("HTTP " + code + " for " + urlStr);
		try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
			return out.toString(StandardCharsets.UTF_8);
		}
	}
}
