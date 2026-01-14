package com.dreamteam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.dreamteam.core.InvertedIndexer;
import com.dreamteam.core.MetadataCatalogBuilder;
import com.dreamteam.datamart.DatamartInitializer;
import com.dreamteam.datamart.MetadataStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class IndexerService {

    private static final Gson gson = new Gson();

    private final String datalakePath;
    private final String indexOutputDir;
    private final String catalogOutputPath;
    private final String dbPath;
    private final String indexProgressPath;
    private final String catalogProgressPath;

    public IndexerService(
            String datalakePath,
            String indexOutputDir,
            String catalogOutputPath,
            String dbPath,
            String indexProgressPath,
            String catalogProgressPath) {

        this.datalakePath = datalakePath;
        this.indexOutputDir = indexOutputDir;
        this.catalogOutputPath = catalogOutputPath;
        this.dbPath = dbPath;
        this.indexProgressPath = indexProgressPath;
        this.catalogProgressPath = catalogProgressPath;
    }

    public Map<String, Object> updateBookIndex(int bookId) {
        long start = System.currentTimeMillis();

        try {
            Files.deleteIfExists(Paths.get(indexProgressPath));

            InvertedIndexer indexer = new InvertedIndexer(
                    datalakePath,
                    indexOutputDir,
                    indexProgressPath
            );

            indexer.buildIndex();

            double elapsed = (System.currentTimeMillis() - start) / 1000.0;

            return Map.of(
                    "status", "ok",
                    "message", "TSV index rebuilt successfully (full reindex)",
                    "elapsed_seconds", elapsed
            );

        } catch (Exception e) {
            return Map.of(
                    "status", "error",
                    "message", e.getMessage()
            );
        }
    }

    public Map<String, Object> rebuildIndex() {
        long startTime = System.currentTimeMillis();
        int booksProcessed = 0;

        try {
            Files.deleteIfExists(Paths.get(indexProgressPath));
            Files.deleteIfExists(Paths.get(catalogProgressPath));

            DatamartInitializer.initDatamart(dbPath);

            InvertedIndexer indexer =
                    new InvertedIndexer(datalakePath, indexOutputDir, indexProgressPath);
            indexer.buildIndex();

            MetadataCatalogBuilder catalogBuilder =
                    new MetadataCatalogBuilder(datalakePath, catalogOutputPath, catalogProgressPath);
            catalogBuilder.buildCatalog();

            MetadataStore store = new MetadataStore(dbPath);
            booksProcessed = store.run(catalogOutputPath);

            double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;

            return Map.of(
                    "books_processed", booksProcessed,
                    "elapsed_time", String.format("%.1fs", elapsedSeconds)
            );

        } catch (Exception exception) {
            return Map.of(
                    "books_processed", booksProcessed,
                    "error", exception.getMessage()
            );
        }
    }

    public Map<String, Object> getStatus() {
        try {
            Path indexDir = Paths.get(indexOutputDir);

            long indexSize = folderSize(indexDir);
            long lastUpdateMillis = lastModifiedInFolder(indexDir);
            int booksIndexed = countBooksInCatalog();

            return Map.of(
                    "books_indexed", booksIndexed,
                    "last_update", lastUpdateMillis == 0 ? "never" : String.valueOf(lastUpdateMillis),
                    "index_size_MB", String.format("%.2f", indexSize / (1024.0 * 1024.0))
            );

        } catch (IOException exception) {
            return Map.of("error", exception.getMessage());
        }
    }

    private int countBooksInCatalog() {
        try {
            Path catalogPath = Paths.get(catalogOutputPath);
            if (!Files.exists(catalogPath)) return 0;

            String json = Files.readString(catalogPath);
            Map<?, ?> catalog = gson.fromJson(json, Map.class);

            return (catalog != null) ? catalog.size() : 0;

        } catch (JsonSyntaxException | IOException exception) {
            return 0;
        }
    }

    private long folderSize(Path folder) throws IOException {
        if (!Files.exists(folder)) return 0;

        return Files.walk(folder)
                .filter(Files::isRegularFile)
                .mapToLong(f -> {
                    try {
                        return Files.size(f);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
    }

    private long lastModifiedInFolder(Path folder) throws IOException {
        if (!Files.exists(folder)) return 0;

        return Files.walk(folder)
                .filter(Files::isRegularFile)
                .map(f -> {
                    try {
                        return Files.getLastModifiedTime(f).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .max(Long::compare)
                .orElse(0L);
    }
}
