package com.dreamteam;

import java.util.HashMap;
import java.util.Map;

import com.dreamteam.common.hazelcast.HazelcastManager;
import com.dreamteam.config.ConfigLoader;
import com.dreamteam.indexer.broker.IndexingEventConsumer;
import com.dreamteam.indexer.distributed.DistributedIndexer;
import com.dreamteam.service.IndexerService;

import io.javalin.Javalin;

public class App {

    private static IndexingEventConsumer eventConsumer;
    private static DistributedIndexer distributedIndexer;

    public static void main(String[] args) {

        String datalakePath = ConfigLoader.getProperty("datalake.path", "datalake");
        String indexOutputDir = ConfigLoader.getProperty("index.output.dir", "indexer/tsv-index");
        String catalogOutputPath = ConfigLoader.getProperty("catalog.output.path", "metadata/catalog.json");
        String dbPath = ConfigLoader.getProperty("db.path", "datamart/datamart.db");
        String indexProgressPath = ConfigLoader.getProperty("index.progress.path", "indexer/progress.json");
        String catalogProgressPath = ConfigLoader.getProperty("catalog.progress.path", "metadata/progress_parser.json");
        int port = ConfigLoader.getIntProperty("server.port", 7002);
        boolean brokerEnabled = ConfigLoader.getBooleanProperty("broker.enabled", true);
        boolean hazelcastEnabled = ConfigLoader.getBooleanProperty("hazelcast.enabled", true);

        // Initialize Hazelcast distributed indexer
        if (hazelcastEnabled) {
            distributedIndexer = new DistributedIndexer(datalakePath);
            System.out.println("Hazelcast distributed indexer initialized");
        }

        IndexerService service = new IndexerService(
                datalakePath,
                indexOutputDir,
                catalogOutputPath,
                dbPath,
                indexProgressPath,
                catalogProgressPath
        );

        // Initialize event consumer for broker integration
        if (brokerEnabled) {
            eventConsumer = new IndexingEventConsumer((bookId, path, hash) -> {
                System.out.printf("Event-driven indexing for book %d at path %s%n", bookId, path);
                
                // Index into distributed Hazelcast index
                if (distributedIndexer != null) {
                    var result = distributedIndexer.indexBook(bookId, path, hash);
                    System.out.printf("Distributed index result: %s%n", result);
                }
                
                // Also update legacy TSV index
                service.updateBookIndex(bookId);
            });
            eventConsumer.start();
        }

        String nodeId = ConfigLoader.getNodeId();

        Javalin app = Javalin.create(config ->
                config.http.defaultContentType = "application/json"
        ).start(port);

        System.out.printf("[%s] Indexer Service started on port %d%n", nodeId, port);

        app.get("/status", ctx -> {
            Map<String, Object> status = new HashMap<>();
            status.put("service", "indexer-service");
            status.put("nodeId", nodeId);
            status.put("status", "running");
            if (eventConsumer != null) {
                status.put("broker_connected", eventConsumer.isRunning());
                status.put("messages_processed", eventConsumer.getMessagesProcessed());
                status.put("duplicates_skipped", eventConsumer.getDuplicatesSkipped());
            }
            if (distributedIndexer != null) {
                status.put("hazelcast_enabled", true);
                status.put("hazelcast_cluster_size", HazelcastManager.getClusterSize());
            }
            ctx.json(status);
        });

        app.get("/index/status", ctx ->
                ctx.json(service.getStatus())
        );

        // Distributed index stats
        app.get("/index/distributed/stats", ctx -> {
            if (distributedIndexer != null) {
                ctx.json(distributedIndexer.getStats());
            } else {
                ctx.status(503).json(Map.of("error", "Hazelcast not enabled"));
            }
        });

        // Index single book into distributed index
        app.post("/index/distributed/{book_id}", ctx -> {
            if (distributedIndexer == null) {
                ctx.status(503).json(Map.of("error", "Hazelcast not enabled"));
                return;
            }
            try {
                int bookId = Integer.parseInt(ctx.pathParam("book_id"));
                String path = ctx.queryParam("path");
                String hash = ctx.queryParam("hash");
                
                if (path == null || hash == null) {
                    ctx.status(400).json(Map.of("error", "Missing path or hash query params"));
                    return;
                }
                
                var result = distributedIndexer.indexBook(bookId, path, hash);
                ctx.json(result);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid book_id format"));
            }
        });

        // Rebuild distributed index
        app.post("/index/distributed/rebuild", ctx -> {
            if (distributedIndexer == null) {
                ctx.status(503).json(Map.of("error", "Hazelcast not enabled"));
                return;
            }
            var result = distributedIndexer.rebuildIndex();
            ctx.json(result);
        });

        app.post("/index/update/{book_id}", ctx -> {
            try {
                int bookId = Integer.parseInt(ctx.pathParam("book_id"));
                ctx.json(service.updateBookIndex(bookId));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid book_id format"));
            }
        });

        app.post("/index/rebuild", ctx ->
                ctx.json(service.rebuildIndex())
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Indexer Service...");
            if (eventConsumer != null) {
                eventConsumer.close();
            }
            HazelcastManager.shutdown();
            app.stop();
        }));
    }
}
