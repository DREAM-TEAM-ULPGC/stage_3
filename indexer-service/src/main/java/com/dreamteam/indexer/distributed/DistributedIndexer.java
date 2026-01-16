package com.dreamteam.indexer.distributed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.dreamteam.common.hazelcast.DistributedIndex;
import com.dreamteam.common.util.HashUtil;
import com.dreamteam.indexer.broker.IndexingEventConsumer;

/**
 * Indexes documents into Hazelcast distributed maps.
 */
public class DistributedIndexer {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-záéíóúüñ]+\\b");

    private final DistributedIndex distributedIndex;
    private final Path datalakeBase;

    public DistributedIndexer(String datalakePath) {
        this.distributedIndex = new DistributedIndex();
        this.datalakeBase = Paths.get(datalakePath);
    }

    public Map<String, Object> indexBook(int bookId, String relativePath, String contentHash) {
        String idempotencyKey = bookId + ":" + contentHash;

        if (distributedIndex.isDocumentProcessed(idempotencyKey)) {
            throw new IndexingEventConsumer.DuplicateIndexRequestException("Duplicate request: " + idempotencyKey);
        }

        Path bookDir = datalakeBase.resolve(relativePath);
        Path bodyFile = bookDir.resolve("body.txt");

        if (!Files.exists(bodyFile)) {
            return Map.of(
                    "status", "error",
                    "message", "body.txt not found",
                    "bookId", bookId,
                    "path", relativePath
            );
        }

        try {
            String text = Files.readString(bodyFile).toLowerCase();
            Map<String, List<Integer>> termPositions = tokenizeWithPositions(text);

            int termsIndexed = distributedIndex.indexDocument(bookId, termPositions);
            distributedIndex.markDocumentProcessed(idempotencyKey);

            return Map.of(
                    "status", "ok",
                    "bookId", bookId,
                    "termsIndexed", termsIndexed,
                    "idempotencyKey", idempotencyKey,
                    "clusterSize", distributedIndex.getStats().get("cluster_size")
            );

        } catch (IOException e) {
            return Map.of(
                    "status", "error",
                    "bookId", bookId,
                    "message", e.getMessage()
            );
        }
    }

    public Map<String, Object> rebuildIndex() {
        long start = System.currentTimeMillis();

        distributedIndex.clear();

        AtomicInteger docs = new AtomicInteger(0);
        AtomicInteger terms = new AtomicInteger(0);

        if (!Files.exists(datalakeBase)) {
            return Map.of("status", "error", "message", "datalake path not found: " + datalakeBase);
        }

        try {
            // Find all body.txt files under datalake and infer bookId from folder name.
            List<Path> bodies;
            try (var stream = Files.walk(datalakeBase)) {
                bodies = stream
                        .filter(p -> p.getFileName().toString().equals("body.txt"))
                        .collect(Collectors.toList());
            }

            // Process books in parallel for better performance
            bodies.parallelStream().forEach(body -> {
                try {
                    Path bookFolder = body.getParent();
                    int bookId;
                    try {
                        bookId = Integer.parseInt(bookFolder.getFileName().toString());
                    } catch (NumberFormatException ignore) {
                        return;
                    }

                    String rel = datalakeBase.toAbsolutePath().relativize(bookFolder.toAbsolutePath()).toString().replace("\\\\", "/");
                    String raw = Files.readString(body);
                    String hash = HashUtil.sha256(raw);

                    Map<String, Object> result = indexBook(bookId, rel, hash);
                    if ("ok".equals(result.get("status"))) {
                        docs.incrementAndGet();
                        terms.addAndGet((int) result.getOrDefault("termsIndexed", 0));
                    }
                } catch (IOException e) {
                    System.err.printf("Error indexing %s: %s%n", body, e.getMessage());
                }
            });

            double elapsed = (System.currentTimeMillis() - start) / 1000.0;
            Map<String, Object> stats = new HashMap<>(distributedIndex.getStats());
            stats.put("status", "ok");
            stats.put("documentsIndexed", docs.get());
            stats.put("termsIndexed", terms.get());
            stats.put("elapsed_seconds", elapsed);
            return stats;

        } catch (IOException e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    public Map<String, Object> getStats() {
        return distributedIndex.getStats();
    }

    private static Map<String, List<Integer>> tokenizeWithPositions(String text) {
        Matcher matcher = WORD_PATTERN.matcher(text);
        Map<String, List<Integer>> termPositions = new HashMap<>();

        int pos = 0;
        while (matcher.find()) {
            String word = matcher.group();
            termPositions.computeIfAbsent(word, k -> new ArrayList<>()).add(pos);
            pos++;
        }

        return termPositions;
    }
}
