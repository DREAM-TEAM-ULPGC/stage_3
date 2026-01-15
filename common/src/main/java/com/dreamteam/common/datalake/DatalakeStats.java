package com.dreamteam.common.datalake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.dreamteam.common.config.ClusterConfig;

/**
 * Simple datalake stats helper used by ingestion-service endpoints.
 */
public class DatalakeStats {

    private final Path datalakeDir;

    public DatalakeStats() {
        this(ClusterConfig.getDatalakeDir());
    }

    public DatalakeStats(String datalakeDir) {
        this.datalakeDir = Paths.get(datalakeDir);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("datalake_dir", datalakeDir.toString());

        if (!Files.exists(datalakeDir)) {
            stats.put("exists", false);
            stats.put("books", 0);
            stats.put("bytes", 0);
            return stats;
        }

        stats.put("exists", true);

        long bytes = 0;
        long books = 0;

        try (Stream<Path> files = Files.walk(datalakeDir)) {
            bytes = files.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException ignore) {
        }

        // Count book folders as directories that contain body.txt
        try (Stream<Path> files = Files.walk(datalakeDir)) {
            books = files.filter(p -> p.getFileName().toString().equals("body.txt"))
                    .count();
        } catch (IOException ignore) {
        }

        stats.put("books", books);
        stats.put("bytes", bytes);
        return stats;
    }

    public Map<String, Object> getBookReplicationStatus(int bookId) {
        Map<String, Object> status = new HashMap<>();
        status.put("bookId", bookId);

        if (!Files.exists(datalakeDir)) {
            status.put("available", false);
            return status;
        }

        // Find any directory named {bookId} that contains body.txt
        try (Stream<Path> dirs = Files.walk(datalakeDir)) {
            Path bookFolder = dirs
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals(String.valueOf(bookId)))
                    .findFirst()
                    .orElse(null);

            if (bookFolder == null) {
                status.put("available", false);
                return status;
            }

            Path raw = bookFolder.resolve("raw.txt");
            Path header = bookFolder.resolve("header.txt");
            Path body = bookFolder.resolve("body.txt");

            status.put("available", Files.exists(body));
            status.put("path", datalakeDir.toAbsolutePath().relativize(bookFolder.toAbsolutePath()).toString().replace("\\", "/"));
            status.put("raw", Files.exists(raw));
            status.put("header", Files.exists(header));
            status.put("body", Files.exists(body));

            return status;

        } catch (IOException e) {
            status.put("available", false);
            status.put("error", e.getMessage());
            return status;
        }
    }
}
