package com.dreamteam.ingestion.broker;

import javax.jms.JMSException;

import com.dreamteam.common.broker.ReconnectingBrokerClient;
import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.model.IndexRequest;
import com.dreamteam.common.util.HashUtil;

/**
 * Publishes ingestion events to the message broker.
 * After a book is successfully ingested and stored in the datalake,
 * this publisher notifies the indexer service to process it.
 */
public class IngestionEventPublisher implements AutoCloseable {

    private final ReconnectingBrokerClient brokerClient;
    private final String nodeId;
    private boolean enabled;

    public IngestionEventPublisher() {
        this.brokerClient = new ReconnectingBrokerClient();
        this.nodeId = ClusterConfig.getNodeId();
        this.enabled = ClusterConfig.getBoolean("broker.enabled", true);
    }

    /**
     * Initializes connection to the broker.
     * Should be called at application startup.
     */
    public void start() {
        if (!enabled) {
            System.out.println("Broker publishing is disabled");
            return;
        }

        try {
            brokerClient.connectWithRetry();
            System.out.println("IngestionEventPublisher started for node: " + nodeId);
        } catch (Exception e) {
            System.err.println("Failed to start IngestionEventPublisher: " + e.getMessage());
        }
    }

    /**
     * Publishes an indexing request after successful ingestion.
     *
     * @param bookId       the Gutenberg book ID
     * @param datalakePath relative path within the datalake
     * @param content      book content for hash computation
     */
    public void publishIndexRequest(int bookId, String datalakePath, String content) {
        if (!enabled) {
            System.out.println("Broker disabled - skipping publish for book " + bookId);
            return;
        }

        try {
            String contentHash = HashUtil.sha256(content);
            IndexRequest request = new IndexRequest(bookId, nodeId, datalakePath, contentHash);

            brokerClient.publish(request);
                System.out.printf("[%s] Published IndexRequest: book=%d, path=%s, hash=%s%n",
                    nodeId, bookId, datalakePath, HashUtil.quickHash(content));

        } catch (JMSException e) {
            System.err.println("Failed to publish index request for book " + bookId + ": " + e.getMessage());
            // In production, you might want to store failed requests for retry
        }
    }

    /**
     * Publishes an indexing request with pre-computed hash.
     */
    public void publishIndexRequest(int bookId, String datalakePath, byte[] contentBytes) {
        if (!enabled) return;

        try {
            String contentHash = HashUtil.sha256(contentBytes);
            IndexRequest request = new IndexRequest(bookId, nodeId, datalakePath, contentHash);
            brokerClient.publish(request);

        } catch (JMSException e) {
            System.err.println("Failed to publish index request for book " + bookId + ": " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return enabled && brokerClient.isConnected();
    }

    @Override
    public void close() {
        brokerClient.close();
        System.out.println("IngestionEventPublisher stopped");
    }
}
