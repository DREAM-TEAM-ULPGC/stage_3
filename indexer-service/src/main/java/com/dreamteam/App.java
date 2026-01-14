package com.dreamteam;

import java.util.Map;

import com.dreamteam.config.ConfigLoader;
import com.dreamteam.service.IndexerService;

import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {

        String datalakePath = ConfigLoader.getProperty("datalake.path", "datalake");
        String indexOutputDir = ConfigLoader.getProperty("index.output.dir", "indexer/tsv-index");
        String catalogOutputPath = ConfigLoader.getProperty("catalog.output.path", "metadata/catalog.json");
        String dbPath = ConfigLoader.getProperty("db.path", "datamart/datamart.db");
        String indexProgressPath = ConfigLoader.getProperty("index.progress.path", "indexer/progress.json");
        String catalogProgressPath = ConfigLoader.getProperty("catalog.progress.path", "metadata/progress_parser.json");
        int port = ConfigLoader.getIntProperty("server.port", 7002);

        IndexerService service = new IndexerService(
                datalakePath,
                indexOutputDir,
                catalogOutputPath,
                dbPath,
                indexProgressPath,
                catalogProgressPath
        );

        Javalin app = Javalin.create(config ->
                config.http.defaultContentType = "application/json"
        ).start(port);

        System.out.println("Indexer Service started on port " + port);

        app.get("/status", ctx ->
                ctx.json(Map.of("service", "indexer-service", "status", "running"))
        );

        app.get("/index/status", ctx ->
                ctx.json(service.getStatus())
        );

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
            app.stop();
        }));
    }
}
