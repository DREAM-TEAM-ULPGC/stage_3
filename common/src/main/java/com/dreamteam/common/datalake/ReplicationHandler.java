package com.dreamteam.common.datalake;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.model.ReplicationResponse;
import com.dreamteam.common.util.HashUtil;

/**
 * Handles incoming replication requests.
 * Stores raw/header/body files into the local datalake directory.
 */
public class ReplicationHandler {

    private final Path datalakeDir;

    public ReplicationHandler() {
        this(ClusterConfig.getDatalakeDir());
    }

    public ReplicationHandler(String datalakeDir) {
        this.datalakeDir = Paths.get(datalakeDir);
    }

    public ReplicationResponse handleReplication(
            int bookId,
            String sourceNodeId,
            String relativePath,
            String rawContentB64,
            String headerContentB64,
            String bodyContentB64,
            String contentHash) {

        String localNodeId = ClusterConfig.getNodeId();

        try {
            byte[] raw = Base64.getDecoder().decode(rawContentB64);
            byte[] header = Base64.getDecoder().decode(headerContentB64);
            byte[] body = Base64.getDecoder().decode(bodyContentB64);

            String computed = HashUtil.sha256(raw);
            if (contentHash != null && !contentHash.isBlank() && !contentHash.equals(computed)) {
                return ReplicationResponse.failure(localNodeId, bookId,
                        "Hash mismatch (expected=" + contentHash + ", computed=" + computed + ")");
            }

            Path bookDir = datalakeDir.resolve(relativePath);
            Files.createDirectories(bookDir);

            Files.write(bookDir.resolve("raw.txt"), raw, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(bookDir.resolve("header.txt"), header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(bookDir.resolve("body.txt"), body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Best-effort: append to ingestions log if configured like ingestion-service does.
            // Not required for operation, but helpful for listBooks().
            try {
                Path logFile = datalakeDir.resolve("ingestions.log");
                String line = java.time.LocalDateTime.now() + ";book=" + bookId + ";path=" + normalizePath(relativePath) + ";bytes=" + raw.length;
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignore) {
            }

            System.out.printf("[%s] Replicated book %d from %s into %s%n", localNodeId, bookId, sourceNodeId, bookDir);

            return ReplicationResponse.success(localNodeId, bookId);

        } catch (Exception e) {
            return ReplicationResponse.failure(localNodeId, bookId, e.getMessage());
        }
    }

    private static String normalizePath(String p) {
        return p == null ? "" : p.replace("\\", "/");
    }
}
