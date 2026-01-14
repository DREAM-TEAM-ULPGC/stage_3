package com.dreamteam.ingestion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.dreamteam.ingestion.broker.IngestionEventPublisher;
import com.dreamteam.ingestion.replication.DatalakeReplicator;

public class IngestionService {

	public record IngestionResult(String status, String path, int replicas) {
		public IngestionResult(String status, String path) {
			this(status, path, 0);
		}
	}

	private final Path datalakeDir;
	private final Path logFile;
	private final IngestionEventPublisher eventPublisher;
	private final DatalakeReplicator replicator;

	public IngestionService(String datalakeDir, String logFile) {
		this(datalakeDir, logFile, null, null);
	}

	public IngestionService(String datalakeDir, String logFile, IngestionEventPublisher eventPublisher) {
		this(datalakeDir, logFile, eventPublisher, null);
	}

	public IngestionService(String datalakeDir, String logFile, 
							IngestionEventPublisher eventPublisher, DatalakeReplicator replicator) {
		this.datalakeDir = Paths.get(datalakeDir);
		this.logFile = Paths.get(logFile);
		this.eventPublisher = eventPublisher;
		this.replicator = replicator;
	}

	public IngestionResult ingest(int bookId) {
		try {
			Optional<Path> existing = findExistingBook(bookId);
			if (existing.isPresent()) {
				return new IngestionResult("available", relativize(existing.get()));
			}

			LocalDateTime now = LocalDateTime.now();
			String day = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
			String hour = now.format(DateTimeFormatter.ofPattern("HH"));
			Path base = datalakeDir.resolve(day).resolve(hour).resolve(String.valueOf(bookId));
			Files.createDirectories(base);

			String raw = BookDownloader.downloadBookPlainText(bookId);
			Path rawFile = base.resolve("raw.txt");
			Files.writeString(rawFile, raw, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

			var parts = GutenbergSplitter.splitHeaderBody(raw);
			Files.writeString(base.resolve("header.txt"), parts.header(), StandardCharsets.UTF_8);
			Files.writeString(base.resolve("body.txt"), parts.body(), StandardCharsets.UTF_8);

			String relativePath = relativize(base);
			log(String.format("%s;book=%d;path=%s;bytes=%d",
					now.toString(), bookId, relativePath, raw.length()));

			// Replicate to peer nodes for fault tolerance
			int replicaCount = 0;
			if (replicator != null && replicator.isEnabled()) {
				replicaCount = replicator.replicateBook(bookId, relativePath, raw, parts.header(), parts.body());
			}

			// Publish indexing event to message broker
			if (eventPublisher != null) {
				eventPublisher.publishIndexRequest(bookId, relativePath, raw);
			}

			return new IngestionResult("downloaded", relativePath, replicaCount);
		} catch (Exception exception) {
			return new IngestionResult("error", exception.getMessage());
		}
	}

	public String status(int bookId) {
		return findExistingBook(bookId).isPresent() ? "available" : "missing";
	}

	public List<Integer> listBooks() {
		if (!Files.exists(datalakeDir)) return List.of();

		try (Stream<String> lines = Files.lines(logFile)) {
			return lines
				.filter(line -> line.contains(";book="))
				.map(line -> line.substring(line.indexOf(";book=") + 6))
				.map(line -> line.substring(0, line.indexOf(";")))
				.map(Integer::parseInt)
				.distinct()
				.sorted()
				.toList();
		} catch (IOException exception) {
			return List.of();
		}
	}

	public Optional<Path> findExistingBook(int bookId) {
		if (!Files.exists(logFile)) return Optional.empty();

		try (Stream<String> lines = Files.lines(logFile)) {
			Optional<String> logEntry = lines
				.filter(line -> line.contains(String.format(";book=%d;", bookId)))
				.findFirst();

			if (logEntry.isPresent()) {
				String line = logEntry.get();
				int pathStart = line.indexOf("path=") + 5;
				int pathEnd = line.indexOf(";bytes=");
				
				if (pathEnd == -1) pathEnd = line.length(); 

				String relativePath = line.substring(pathStart, pathEnd);
				return Optional.of(datalakeDir.resolve(relativePath));
			}

		} catch (IOException exception) {
		}
		return Optional.empty(); 
	}

	private String relativize(Path path) {
		try {
			return datalakeDir.toAbsolutePath().relativize(path.toAbsolutePath()).toString().replace("\\","/");
		} catch (Exception ignored) {
			return path.toString().replace("\\","/");
		}
	}

	private void log(String line) {
		try {
			Files.createDirectories(logFile.getParent());
			Files.writeString(logFile, line + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {}
	}
}
