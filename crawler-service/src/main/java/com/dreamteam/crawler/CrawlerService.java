package com.dreamteam.crawler;

import com.google.gson.Gson;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Crawler Service - Downloads books from Project Gutenberg and publishes events to ActiveMQ.
 * Implements distributed datalake with date-based organization (YYYYMMDD/HH/) as per Stage 1.
 * Separates header/body using Gutenberg markers as per Stage 1 specification.
 * Extracts metadata (title, author, language, year) from header as per Stage 2 specification.
 */
public class CrawlerService {
    private static final Logger logger = LoggerFactory.getLogger(CrawlerService.class);
    private static final Gson gson = new Gson();

    // Gutenberg markers for content separation (Stage 1 requirement)
    private static final String START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

    // Metadata regex patterns for header parsing (Stage 2 requirement)
    private static final java.util.regex.Pattern TITLE_PATTERN = 
        java.util.regex.Pattern.compile("Title:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern AUTHOR_PATTERN = 
        java.util.regex.Pattern.compile("Author:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LANGUAGE_PATTERN = 
        java.util.regex.Pattern.compile("Language:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RELEASE_DATE_PATTERN = 
        java.util.regex.Pattern.compile("Release [Dd]ate:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern YEAR_PATTERN = 
        java.util.regex.Pattern.compile("(\\d{4})");

    // Configuration - aligned with docker-compose environment variables
    private final String brokerUrl;
    private final String datalakePath;
    private final String nodeId;
    private final int replicationFactor;
    private final int totalNodes;

    // JMS components
    private Connection connection;
    private Session session;
    private MessageProducer documentProducer;

    // Date formatters for datalake structure
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH");

    // Book IDs to crawl (subset of Gutenberg - classic literature)
    private static final int[] BOOK_IDS = {
        1, 2, 11, 12, 16, 17, 23, 25, 28, 29,
        30, 31, 33, 36, 37, 41, 43, 45, 46, 47,
        55, 74, 76, 84, 98, 100, 103, 105, 120, 140,
        145, 158, 161, 174, 176, 184, 205, 209, 215, 219,
        236, 244, 345, 394, 408, 521, 730, 768, 790, 829,
        844, 863, 910, 932, 940, 996, 1080, 1155, 1184, 1232,
        1250, 1257, 1260, 1322, 1342, 1400, 1497, 1661, 1727, 1952,
        2000, 2130, 2148, 2160, 2500, 2554, 2591, 2600, 2701, 2814,
        3207, 3600, 4085, 4300, 5000, 5200, 6130, 7370, 8492, 8800
    };

    public CrawlerService() {
        // Environment variables aligned with docker-compose.yml
        this.brokerUrl = getEnv("ACTIVEMQ_URL", "tcp://activemq:61616");
        this.datalakePath = getEnv("DATALAKE_PATH", "/data/datalake");
        this.nodeId = getEnv("CRAWLER_ID", "crawler1");
        this.replicationFactor = Integer.parseInt(getEnv("REPLICATION_FACTOR", "2"));
        this.totalNodes = Integer.parseInt(getEnv("TOTAL_NODES", "3"));
    }

    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    public void start() throws Exception {
        logger.info("Starting Crawler Service - Node: {}", nodeId);
        logger.info("Configuration - Broker: {}, Datalake: {}, Replication: {}",
            brokerUrl, datalakePath, replicationFactor);

        // Ensure base datalake directory exists
        Files.createDirectories(Paths.get(datalakePath));

        // Connect to ActiveMQ with retry
        connectToActiveMQ();

        // Start crawling
        crawlBooks();

        logger.info("Crawler Service completed crawling phase");
    }

    private void connectToActiveMQ() throws JMSException, InterruptedException {
        int maxRetries = 30;
        int retryDelay = 2000;

        for (int i = 0; i < maxRetries; i++) {
            try {
                ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
                factory.setTrustAllPackages(true);
                connection = factory.createConnection();
                connection.start();
                session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

                // Create queue for document events (Stage 3 requirement)
                Queue documentQueue = session.createQueue("document.ingested");
                documentProducer = session.createProducer(documentQueue);
                documentProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

                logger.info("Connected to ActiveMQ broker at {}", brokerUrl);
                return;
            } catch (JMSException e) {
                logger.warn("Failed to connect to ActiveMQ (attempt {}/{}): {}",
                    i + 1, maxRetries, e.getMessage());
                if (i < maxRetries - 1) {
                    Thread.sleep(retryDelay);
                }
            }
        }
        throw new RuntimeException("Could not connect to ActiveMQ after " + maxRetries + " attempts");
    }

    private void crawlBooks() {
        logger.info("Starting to crawl {} books", BOOK_IDS.length);

        // Determine which books this node should process (partitioning for distributed crawling)
        int nodeIndex = extractNodeIndex(nodeId);

        int processedCount = 0;
        int errorCount = 0;

        for (int i = 0; i < BOOK_IDS.length; i++) {
            // Distributed partitioning: each crawler processes its subset
            if (i % totalNodes != nodeIndex) {
                continue;
            }

            int bookId = BOOK_IDS[i];
            try {
                boolean success = downloadAndProcessBook(bookId);
                if (success) {
                    processedCount++;
                }
            } catch (Exception e) {
                logger.error("Error processing book {}: {}", bookId, e.getMessage());
                errorCount++;
            }

            // Rate limiting to avoid overwhelming Gutenberg
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.info("Crawling complete - Processed: {}, Errors: {}", processedCount, errorCount);
    }

    private int extractNodeIndex(String nodeId) {
        try {
            String numStr = nodeId.replaceAll("[^0-9]", "");
            return Integer.parseInt(numStr) - 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean downloadAndProcessBook(int bookId) throws Exception {
        // Try both URL formats as per Gutenberg
        String url = String.format("https://www.gutenberg.org/cache/epub/%d/pg%d.txt", bookId, bookId);
        String altUrl = String.format("https://www.gutenberg.org/files/%d/%d-0.txt", bookId, bookId);

        String rawContent = downloadContent(url);
        if (rawContent == null || rawContent.isEmpty()) {
            rawContent = downloadContent(altUrl);
        }

        if (rawContent == null || rawContent.isEmpty()) {
            logger.warn("Could not download book {}", bookId);
            return false;
        }

        // Check for Gutenberg markers (Stage 1 requirement)
        if (!rawContent.contains(START_MARKER) || !rawContent.contains(END_MARKER)) {
            logger.warn("Book {} does not contain valid Gutenberg markers", bookId);
            return false;
        }

        // Split into header and body as per Stage 1 specification
        String[] parts = rawContent.split("\\*\\*\\* START OF THE PROJECT GUTENBERG EBOOK", 2);
        String header = parts[0].trim();
        
        String bodyAndFooter = parts.length > 1 ? parts[1] : "";
        String[] bodyParts = bodyAndFooter.split("\\*\\*\\* END OF THE PROJECT GUTENBERG EBOOK", 2);
        String body = bodyParts[0].trim();

        // Save to datalake with date-based structure (Stage 1: YYYYMMDD/HH/)
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_FORMAT);
        String hourPath = now.format(HOUR_FORMAT);

        Path datalakeDir = Paths.get(datalakePath, datePath, hourPath);
        Files.createDirectories(datalakeDir);

        // Save header and body separately as per Stage 1
        Path headerPath = datalakeDir.resolve(bookId + ".header.txt");
        Path bodyPath = datalakeDir.resolve(bookId + ".body.txt");

        Files.writeString(headerPath, header, StandardCharsets.UTF_8);
        Files.writeString(bodyPath, body, StandardCharsets.UTF_8);

        logger.debug("Saved book {} to datalake: {}", bookId, datalakeDir);

        // Extract metadata from header (Stage 2 requirement)
        Map<String, String> metadata = extractMetadata(header);

        // Publish event to ActiveMQ for indexing (Stage 3 requirement)
        publishDocumentEvent(bookId, body, bodyPath.toString(), metadata);

        logger.info("Processed book {} - Header: {} bytes, Body: {} bytes, Title: {}",
            bookId, header.length(), body.length(), metadata.get("title"));
        return true;
    }

    /**
     * Extract metadata (title, author, language, year) from Gutenberg header.
     * Stage 2 requirement: Search results must include these fields.
     */
    private Map<String, String> extractMetadata(String header) {
        Map<String, String> metadata = new HashMap<>();
        
        // Extract title
        var titleMatcher = TITLE_PATTERN.matcher(header);
        if (titleMatcher.find()) {
            metadata.put("title", titleMatcher.group(1).trim());
        } else {
            metadata.put("title", "Unknown Title");
        }
        
        // Extract author
        var authorMatcher = AUTHOR_PATTERN.matcher(header);
        if (authorMatcher.find()) {
            metadata.put("author", authorMatcher.group(1).trim());
        } else {
            metadata.put("author", "Unknown Author");
        }
        
        // Extract language (convert to ISO 639-1 code)
        var languageMatcher = LANGUAGE_PATTERN.matcher(header);
        if (languageMatcher.find()) {
            String lang = languageMatcher.group(1).trim().toLowerCase();
            metadata.put("language", convertToLanguageCode(lang));
        } else {
            metadata.put("language", "en");
        }
        
        // Extract year from release date
        var releaseDateMatcher = RELEASE_DATE_PATTERN.matcher(header);
        if (releaseDateMatcher.find()) {
            String releaseDate = releaseDateMatcher.group(1);
            var yearMatcher = YEAR_PATTERN.matcher(releaseDate);
            if (yearMatcher.find()) {
                metadata.put("year", yearMatcher.group(1));
            } else {
                metadata.put("year", "0");
            }
        } else {
            metadata.put("year", "0");
        }
        
        return metadata;
    }

    /**
     * Convert language name to ISO 639-1 code as per Stage 2 requirement.
     */
    private String convertToLanguageCode(String language) {
        return switch (language) {
            case "english" -> "en";
            case "french", "français" -> "fr";
            case "german", "deutsch" -> "de";
            case "spanish", "español" -> "es";
            case "italian", "italiano" -> "it";
            case "portuguese", "português" -> "pt";
            case "dutch", "nederlands" -> "nl";
            case "latin" -> "la";
            case "greek" -> "el";
            default -> language.length() == 2 ? language : "en";
        };
    }

    private String downloadContent(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "DreamTeam-SearchEngine/1.0 (University Project)");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                return content.toString();
            }
        } catch (Exception e) {
            logger.debug("Failed to download {}: {}", urlStr, e.getMessage());
            return null;
        }
    }

    private void publishDocumentEvent(int bookId, String body, String filePath, Map<String, String> metadata) throws JMSException {
        // Create document event for indexer with metadata (Stage 2 requirement)
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "document.ingested");
        event.put("bookId", bookId);
        event.put("documentId", "gutenberg_" + bookId);
        event.put("content", body);
        event.put("filePath", filePath);
        event.put("timestamp", Instant.now().toString());
        event.put("sourceNode", nodeId);
        event.put("contentLength", body.length());
        event.put("contentHash", computeHash(body));
        
        // Include metadata for Stage 2 compliance (title, author, language, year)
        event.put("title", metadata.get("title"));
        event.put("author", metadata.get("author"));
        event.put("language", metadata.get("language"));
        event.put("year", metadata.get("year"));

        String jsonMessage = gson.toJson(event);
        TextMessage message = session.createTextMessage(jsonMessage);
        message.setStringProperty("documentId", "gutenberg_" + bookId);
        message.setStringProperty("sourceNode", nodeId);
        message.setIntProperty("bookId", bookId);

        documentProducer.send(message);
        logger.debug("Published document.ingested event for book {}", bookId);
    }

    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    public void shutdown() {
        logger.info("Shutting down Crawler Service");
        try {
            if (documentProducer != null) documentProducer.close();
            if (session != null) session.close();
            if (connection != null) connection.close();
        } catch (JMSException e) {
            logger.error("Error during shutdown: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        CrawlerService crawler = new CrawlerService();

        Runtime.getRuntime().addShutdownHook(new Thread(crawler::shutdown));

        try {
            crawler.start();

            // Keep running for potential re-crawling or additional operations
            logger.info("Crawler service completed. Container will stay alive for inspection.");
            Thread.sleep(Long.MAX_VALUE);
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
