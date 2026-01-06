package com.dreamteam.search;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search Service - Provides REST API for querying the Hazelcast inverted index.
 * Sits behind Nginx load balancer as per Stage 3 architecture.
 * Returns results with metadata (title, author, language, year) as per Stage 2 API.
 * Supports filtering by author, language, and year as per Stage 2 specification.
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    // Configuration - aligned with docker-compose environment variables
    private final String hazelcastMembers;
    private final String nodeId;
    private final int httpPort;

    // Hazelcast - client connection to cluster
    private HazelcastInstance hazelcast;
    private MultiMap<String, String> invertedIndex;
    private IMap<String, Map<String, String>> documentMetadata;

    // Web server (Javalin)
    private Javalin app;

    // Statistics for benchmarking
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong totalQueryTime = new AtomicLong(0);

    // Tokenization
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+");

    public SearchService() {
        // Environment variables aligned with docker-compose.yml
        this.hazelcastMembers = getEnv("HAZELCAST_MEMBERS", "hazelcast1:5701,hazelcast2:5701,hazelcast3:5701");
        this.nodeId = getEnv("SEARCH_ID", "search1");
        this.httpPort = Integer.parseInt(getEnv("SERVER_PORT", "8080"));
    }

    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    public void start() throws Exception {
        logger.info("Starting Search Service - Node: {}", nodeId);
        logger.info("Configuration - Hazelcast: {}, HTTP Port: {}", hazelcastMembers, httpPort);

        // Connect to Hazelcast cluster
        connectToHazelcast();

        // Start HTTP server
        startHttpServer();

        logger.info("Search Service started successfully on port {}", httpPort);
    }

    private void connectToHazelcast() throws InterruptedException {
        int maxRetries = 30;
        int retryDelay = 2000;

        for (int i = 0; i < maxRetries; i++) {
            try {
                ClientConfig clientConfig = new ClientConfig();
                clientConfig.setClusterName("search-cluster");

                // Parse cluster addresses from environment
                String[] addresses = hazelcastMembers.split(",");
                for (String address : addresses) {
                    clientConfig.getNetworkConfig().addAddress(address.trim());
                }

                // Connection retry configuration
                clientConfig.getConnectionStrategyConfig()
                    .getConnectionRetryConfig()
                    .setInitialBackoffMillis(1000)
                    .setMaxBackoffMillis(30000)
                    .setMultiplier(2)
                    .setClusterConnectTimeoutMillis(60000);

                hazelcast = HazelcastClient.newHazelcastClient(clientConfig);

                // Get MultiMap for inverted index (read-only access)
                invertedIndex = hazelcast.getMultiMap("inverted-index");
                
                // Get IMap for document metadata (Stage 2: title, author, language, year)
                documentMetadata = hazelcast.getMap("document-metadata");

                logger.info("Connected to Hazelcast cluster - search-cluster");
                return;
            } catch (Exception e) {
                logger.warn("Failed to connect to Hazelcast (attempt {}/{}): {}",
                    i + 1, maxRetries, e.getMessage());
                if (i < maxRetries - 1) {
                    Thread.sleep(retryDelay);
                }
            }
        }
        throw new RuntimeException("Could not connect to Hazelcast after " + maxRetries + " attempts");
    }

    private void startHttpServer() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "application/json";
        }).start(httpPort);

        // Health check endpoint (for Nginx load balancer)
        app.get("/health", this::handleHealth);

        // Search endpoints
        app.get("/search", this::handleSearch);
        app.get("/query", this::handleSearch);  // Alias

        // Status and stats endpoints
        app.get("/status", this::handleStatus);
        app.get("/admin/stats", this::handleStats);
        app.get("/admin/index-size", this::handleIndexSize);

        // Root endpoint
        app.get("/", ctx -> ctx.json(Map.of(
            "service", "search-service",
            "node", nodeId,
            "endpoints", List.of("/search?q=word", "/health", "/status", "/admin/stats")
        )));

        logger.info("HTTP server started on port {}", httpPort);
    }

    private void handleHealth(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("service", "search-service");
        response.put("node", nodeId);
        response.put("hazelcastConnected", hazelcast != null && hazelcast.getLifecycleService().isRunning());
        response.put("timestamp", System.currentTimeMillis());

        ctx.json(response);
    }

    private void handleSearch(Context ctx) {
        long startTime = System.currentTimeMillis();

        String query = ctx.queryParam("q");
        if (query == null || query.trim().isEmpty()) {
            ctx.status(400).json(Map.of(
                "error", "Missing query parameter 'q'",
                "usage", "/search?q=your+query+here"
            ));
            return;
        }

        // Stage 2 filters: author, language, year
        String authorFilter = ctx.queryParam("author");
        String languageFilter = ctx.queryParam("language");
        String yearFilter = ctx.queryParam("year");

        int limit = 100;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
                limit = Math.max(1, Math.min(1000, limit));
            } catch (NumberFormatException e) {
                // Use default
            }
        }

        try {
            // Tokenize query
            List<String> queryTerms = tokenize(query);

            if (queryTerms.isEmpty()) {
                ctx.json(Map.of(
                    "query", query,
                    "filters", buildFiltersMap(authorFilter, languageFilter, yearFilter),
                    "count", 0,
                    "results", Collections.emptyList()
                ));
                return;
            }

            // Search, filter, and rank results (Stage 2 format)
            List<Map<String, Object>> results = searchWithMetadata(
                queryTerms, authorFilter, languageFilter, yearFilter, limit);

            long queryTime = System.currentTimeMillis() - startTime;
            queryCount.incrementAndGet();
            totalQueryTime.addAndGet(queryTime);

            // Build response in Stage 2 format
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("filters", buildFiltersMap(authorFilter, languageFilter, yearFilter));
            response.put("count", results.size());
            response.put("results", results);

            ctx.json(response);

            logger.debug("Query '{}' returned {} results in {}ms",
                query, results.size(), queryTime);

        } catch (Exception e) {
            logger.error("Search error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of(
                "error", "Search failed",
                "message", e.getMessage()
            ));
        }
    }

    private Map<String, Object> buildFiltersMap(String author, String language, String year) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) filters.put("author", author);
        if (language != null) filters.put("language", language);
        if (year != null) filters.put("year", year);
        return filters;
    }

    private List<String> tokenize(String query) {
        List<String> terms = new ArrayList<>();
        var matcher = WORD_PATTERN.matcher(query.toLowerCase());
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }

    /**
     * Search with metadata and filters as per Stage 2 API specification.
     * Returns results with book_id, title, author, language, year.
     */
    private List<Map<String, Object>> searchWithMetadata(
            List<String> queryTerms, 
            String authorFilter, 
            String languageFilter, 
            String yearFilter,
            int limit) {
        
        // Count document occurrences (TF-based ranking)
        Map<String, Integer> docScores = new HashMap<>();

        for (String term : queryTerms) {
            Collection<String> docIds = invertedIndex.get(term);
            for (String docId : docIds) {
                docScores.merge(docId, 1, Integer::sum);
            }
        }

        // Sort by score descending, get metadata, apply filters
        return docScores.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(entry -> {
                String docId = entry.getKey();
                Map<String, String> metadata = documentMetadata.get(docId);
                
                if (metadata == null) {
                    // Create default metadata if not found
                    metadata = new HashMap<>();
                    metadata.put("title", "Unknown");
                    metadata.put("author", "Unknown");
                    metadata.put("language", "en");
                    metadata.put("year", "0");
                }
                
                // Build result in Stage 2 format
                Map<String, Object> result = new LinkedHashMap<>();
                // Extract book_id from documentId (e.g., "gutenberg_1342" -> 1342)
                String bookIdStr = docId.replace("gutenberg_", "");
                try {
                    result.put("book_id", Integer.parseInt(bookIdStr));
                } catch (NumberFormatException e) {
                    result.put("book_id", docId);
                }
                result.put("title", metadata.get("title"));
                result.put("author", metadata.get("author"));
                result.put("language", metadata.get("language"));
                try {
                    result.put("year", Integer.parseInt(metadata.get("year")));
                } catch (NumberFormatException e) {
                    result.put("year", 0);
                }
                return result;
            })
            // Apply Stage 2 filters
            .filter(result -> {
                if (authorFilter != null && !authorFilter.isEmpty()) {
                    String author = (String) result.get("author");
                    if (author == null || !author.toLowerCase().contains(authorFilter.toLowerCase())) {
                        return false;
                    }
                }
                if (languageFilter != null && !languageFilter.isEmpty()) {
                    String language = (String) result.get("language");
                    if (language == null || !language.equalsIgnoreCase(languageFilter)) {
                        return false;
                    }
                }
                if (yearFilter != null && !yearFilter.isEmpty()) {
                    try {
                        int filterYear = Integer.parseInt(yearFilter);
                        int resultYear = (Integer) result.get("year");
                        if (filterYear != resultYear) {
                            return false;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid year filter
                    }
                }
                return true;
            })
            .limit(limit)
            .collect(Collectors.toList());
    }

    private void handleStatus(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "running");
        response.put("node", nodeId);
        response.put("queriesProcessed", queryCount.get());
        response.put("hazelcastConnected", hazelcast != null && hazelcast.getLifecycleService().isRunning());
        response.put("timestamp", System.currentTimeMillis());

        ctx.json(response);
    }

    private void handleStats(Context ctx) {
        long queries = queryCount.get();
        long totalTime = totalQueryTime.get();
        double avgTime = queries > 0 ? (double) totalTime / queries : 0;

        Map<String, Object> response = new HashMap<>();
        response.put("node", nodeId);
        response.put("totalQueries", queries);
        response.put("totalQueryTimeMs", totalTime);
        response.put("avgQueryTimeMs", Math.round(avgTime * 100) / 100.0);
        response.put("timestamp", System.currentTimeMillis());

        ctx.json(response);
    }

    private void handleIndexSize(Context ctx) {
        int indexSize = invertedIndex.size();
        Set<String> uniqueWords = invertedIndex.keySet();

        Map<String, Object> response = new HashMap<>();
        response.put("totalEntries", indexSize);
        response.put("uniqueWords", uniqueWords.size());
        response.put("node", nodeId);
        response.put("timestamp", System.currentTimeMillis());

        ctx.json(response);
    }

    public void shutdown() {
        logger.info("Shutting down Search Service");

        if (app != null) {
            app.stop();
        }
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    public static void main(String[] args) {
        SearchService search = new SearchService();

        Runtime.getRuntime().addShutdownHook(new Thread(search::shutdown));

        try {
            search.start();

            // Keep running
            logger.info("Search service running on port {}. Ready for queries.",
                search.httpPort);
            Thread.sleep(Long.MAX_VALUE);
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
