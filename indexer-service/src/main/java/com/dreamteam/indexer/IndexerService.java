package com.dreamteam.indexer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Indexer Service - Subscribes to ActiveMQ for document events and maintains
 * inverted index in Hazelcast MultiMap as per Stage 3 specification.
 * 
 * The MultiMap stores word -> Set<documentId> mappings distributed across the cluster.
 */
public class IndexerService implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(IndexerService.class);
    private static final Gson gson = new Gson();

    // Configuration - aligned with docker-compose environment variables
    private final String brokerUrl;
    private final String hazelcastMembers;
    private final String nodeId;

    // JMS components
    private Connection connection;
    private Session session;
    private MessageConsumer documentConsumer;

    // Hazelcast - MultiMap for inverted index (Stage 3 requirement)
    // IMap for document metadata (Stage 2 requirement: title, author, language, year)
    private HazelcastInstance hazelcast;
    private MultiMap<String, String> invertedIndex;
    private IMap<String, Map<String, String>> documentMetadata;

    // Statistics
    private final AtomicLong documentsProcessed = new AtomicLong(0);
    private final AtomicLong wordsIndexed = new AtomicLong(0);

    // Tokenization patterns
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+");
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "shall", "can", "this", "that", "these",
        "those", "i", "you", "he", "she", "it", "we", "they", "what", "which",
        "who", "whom", "their", "its", "his", "her", "my", "your", "our"
    );

    public IndexerService() {
        // Environment variables aligned with docker-compose.yml
        this.brokerUrl = getEnv("ACTIVEMQ_URL", "tcp://activemq:61616");
        this.hazelcastMembers = getEnv("HAZELCAST_MEMBERS", "hazelcast1:5701,hazelcast2:5701,hazelcast3:5701");
        this.nodeId = getEnv("INDEXER_ID", "indexer1");
    }

    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    public void start() throws Exception {
        logger.info("Starting Indexer Service - Node: {}", nodeId);
        logger.info("Configuration - Broker: {}, Hazelcast: {}", brokerUrl, hazelcastMembers);

        // Connect to Hazelcast cluster first
        connectToHazelcast();

        // Then connect to ActiveMQ
        connectToActiveMQ();

        logger.info("Indexer Service started successfully");
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

                // Get MultiMap for inverted index (Stage 3: word -> Set<docId>)
                invertedIndex = hazelcast.getMultiMap("inverted-index");
                
                // Get IMap for document metadata (Stage 2: docId -> {title, author, language, year})
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

                // Subscribe to document.ingested queue
                Queue documentQueue = session.createQueue("document.ingested");
                documentConsumer = session.createConsumer(documentQueue);
                documentConsumer.setMessageListener(this);

                logger.info("Connected to ActiveMQ broker at {} - listening on document.ingested", brokerUrl);
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

    @Override
    public void onMessage(Message message) {
        try {
            if (message instanceof TextMessage textMessage) {
                String json = textMessage.getText();
                processDocumentEvent(json);
            }
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage(), e);
        }
    }

    private void processDocumentEvent(String json) {
        try {
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> event = gson.fromJson(json, type);

            String docId = (String) event.get("documentId");
            String content = (String) event.get("content");
            
            // Extract metadata from event (Stage 2 requirement)
            String title = (String) event.getOrDefault("title", "Unknown Title");
            String author = (String) event.getOrDefault("author", "Unknown Author");
            String language = (String) event.getOrDefault("language", "en");
            String year = event.get("year") != null ? String.valueOf(event.get("year")) : "0";

            if (docId == null || content == null) {
                logger.warn("Invalid document event - missing documentId or content");
                return;
            }
            
            // Store metadata in Hazelcast IMap (Stage 2 requirement)
            Map<String, String> metadata = new HashMap<>();
            metadata.put("title", title);
            metadata.put("author", author);
            metadata.put("language", language);
            metadata.put("year", year);
            documentMetadata.put(docId, metadata);

            // Tokenize and index into Hazelcast MultiMap
            int wordCount = indexDocument(docId, content);

            documentsProcessed.incrementAndGet();
            wordsIndexed.addAndGet(wordCount);

            logger.info("Indexed document {} ({}) - {} unique words (Total: {} docs, {} words)",
                docId, title, wordCount, documentsProcessed.get(), wordsIndexed.get());

        } catch (Exception e) {
            logger.error("Error indexing document: {}", e.getMessage(), e);
        }
    }

    private int indexDocument(String docId, String content) {
        // Tokenize content
        Set<String> uniqueWords = tokenize(content);

        // Add to inverted index: word -> docId (MultiMap handles Set semantics)
        for (String word : uniqueWords) {
            invertedIndex.put(word, docId);
        }

        return uniqueWords.size();
    }

    private Set<String> tokenize(String content) {
        Set<String> words = new HashSet<>();

        var matcher = WORD_PATTERN.matcher(content.toLowerCase());
        while (matcher.find()) {
            String word = matcher.group();
            // Filter: minimum length 3 and not a stop word
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }

        return words;
    }

    public void shutdown() {
        logger.info("Shutting down Indexer Service");
        logger.info("Final stats - Documents: {}, Words indexed: {}",
            documentsProcessed.get(), wordsIndexed.get());

        try {
            if (documentConsumer != null) documentConsumer.close();
            if (session != null) session.close();
            if (connection != null) connection.close();
            if (hazelcast != null) hazelcast.shutdown();
        } catch (Exception e) {
            logger.error("Error during shutdown: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        IndexerService indexer = new IndexerService();

        Runtime.getRuntime().addShutdownHook(new Thread(indexer::shutdown));

        try {
            indexer.start();

            // Keep running to process messages continuously
            logger.info("Indexer service running. Waiting for document.ingested events...");
            Thread.sleep(Long.MAX_VALUE);
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
