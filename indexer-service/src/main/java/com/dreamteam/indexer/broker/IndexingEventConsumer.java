package com.dreamteam.indexer.broker;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.JMSException;

import com.dreamteam.common.broker.ReconnectingBrokerClient;
import com.dreamteam.common.model.IndexRequest;

/**
 * Consumes IndexRequest events from ActiveMQ and dispatches them to a handler.
 */
public class IndexingEventConsumer implements AutoCloseable {

    @FunctionalInterface
    public interface IndexingHandler {
        void handle(IndexRequest request) throws Exception;
    }

    private final ReconnectingBrokerClient brokerClient;
    private final IndexingHandler handler;
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong duplicatesSkipped = new AtomicLong(0);

    private volatile boolean running;

    public IndexingEventConsumer(IndexingHandler handler) {
        this.brokerClient = new ReconnectingBrokerClient();
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public void start() {
        running = true;
        try {
            brokerClient.connectWithRetry();
            brokerClient.subscribe(this::onMessage);
            System.out.println("IndexingEventConsumer started");
        } catch (JMSException e) {
            System.err.println("Failed to start IndexingEventConsumer: " + e.getMessage());
        }
    }

    private void onMessage(IndexRequest request) throws Exception {
        if (!running) return;

        // We always count the message; actual dedup logic is handled downstream (DistributedIndexer).
        messagesProcessed.incrementAndGet();

        try {
            handler.handle(request);
        } catch (DuplicateIndexRequestException dup) {
            duplicatesSkipped.incrementAndGet();
        }
    }

    public boolean isRunning() {
        return running && brokerClient.isConnected();
    }

    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    public long getDuplicatesSkipped() {
        return duplicatesSkipped.get();
    }

    @Override
    public void close() {
        running = false;
        brokerClient.close();
    }

    /**
     * Lightweight signal exception to count duplicates.
     */
    public static class DuplicateIndexRequestException extends RuntimeException {
        public DuplicateIndexRequestException(String message) {
            super(message);
        }
    }
}
