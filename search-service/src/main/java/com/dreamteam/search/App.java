package com.dreamteam.search;

import java.nio.file.Path;

import com.dreamteam.search.util.Config;

import io.javalin.Javalin;

public class App {
    public static void main(String[] args) {
        String indexPath = Config.getEnvOrDefault("index.path", "indexer/inverted_index.json");
        String dbPath = Config.getEnvOrDefault("db.path", "datamart/datamart.db");
        int port = Config.getIntProperty("server.port", 7003);

        SearchEngine engine = new SearchEngine(Path.of(indexPath));
        MetadataDao metadataDao = new MetadataDao("jdbc:sqlite:" + dbPath);

        Javalin app = Javalin.create(conf -> conf.http.defaultContentType = "application/json").start(port);

        app.get("/health", ctx -> ctx.json(new Health("ok", "search", "1.0.0")));

        final long startTime = System.currentTimeMillis();

        app.get("/status", ctx -> {
            java.util.Map<String, Object> status = new java.util.HashMap<>();
            status.put("service", "search-service");
            status.put("status", "running");
            status.put("version", "1.0.0");
            status.put("port", port);
            status.put("indexPath", indexPath);
            status.put("dbPath", dbPath);
            status.put("uptimeSeconds", (System.currentTimeMillis() - startTime) / 1000);
            ctx.json(status);
        });


        app.get("/book/{id}", ctx -> {
            try {
                int id = Integer.parseInt(ctx.pathParam("id"));
                var book = metadataDao.getBookById(id);
                if (book == null) ctx.status(404).result("{\"error\":\"book not found\"}");
                else ctx.json(book);
            } catch (NumberFormatException exception) {
                ctx.status(400).result("{\"error\":\"invalid id\"}");
            }
        });

        app.get("/search", ctx -> {
            String q = ctx.queryParam("q");
            if (q == null || q.isBlank()) {
                ctx.status(400).result("{\"error\":\"missing query param 'q'\"}");
                return;
            }
            String mode = ctx.queryParamAsClass("mode", String.class).getOrDefault("and");
            String author = ctx.queryParam("author");
            String language = ctx.queryParam("language");
            int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
            int pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

            var results = engine.search(q, mode);
            var enriched = metadataDao.enrichAndFilter(results, author, language);

            int total = enriched.size();
            int from = Math.max(0, (page - 1) * pageSize);
            int to = Math.min(total, from + pageSize);
            var pageItems = enriched.subList(from, to);

            var response = new SearchResponse(q, mode, page, pageSize, total, pageItems);
            ctx.json(response);
        });

        app.post("/admin/reload", ctx -> {
            try {
                engine.reload(Path.of(indexPath));
                metadataDao.reload("jdbc:sqlite:" + dbPath);
                ctx.json(new Msg("reloaded"));
            } catch (Exception exception) {
                ctx.status(500).json(new Msg("reload failed: " + exception.getMessage()));
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(metadataDao::close));
    }

    record Health(String status, String service, String version) {}
    record Msg(String message) {}
    record SearchResponse(String query, String mode, int page, int pageSize, int total,
                          java.util.List<SearchEngine.ScoredDoc> items) {}
}
