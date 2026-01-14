package com.dreamteam.common.broker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.JMSException;
import javax.jms.MessageConsumer;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.model.IndexRequest;

/**
 * Enhanced BrokerClient with automatic reconnection capabilities.
 * Suitable for production deployments where broker connectivity may be intermittent.
 */
public class ReconnectingBrokerClient implements AutoCloseable {

    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;

    private final BrokerClient delegate;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private MessageHandler messageHandler;
    private MessageConsumer activeConsumer;

    public ReconnectingBrokerClient() {
        this(ClusterConfig.getBrokerUrl(), ClusterConfig.getIndexingQueue());
    }

    public ReconnectingBrokerClient(String brokerUrl, String queueName) {
        this.delegate = new BrokerClient(brokerUrl, queueName);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "broker-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Connects to the broker with automatic retry on failure.
     */
    public void connectWithRetry() {
        tryConnect();
    }

    private void tryConnect() {
        if (!running.get()) return;

        try {
            delegate.connect();
            consecutiveFailures.set(0);

            // Re-subscribe if we had an active handler
            if (messageHandler != null) {
                subscribe(messageHandler);
            }

            System.out.println("Broker connection established successfully");

        } catch (JMSException e) {
            int failures = consecutiveFailures.incrementAndGet();
            long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << (failures - 1)), MAX_BACKOFF_MS);

            if (failures <= MAX_RETRIES) {
                System.err.printf("Broker connection failed (attempt %d/%d). Retrying in %dms: %s%n",
                        failures, MAX_RETRIES, backoff, e.getMessage());
                scheduler.schedule(this::tryConnect, backoff, TimeUnit.MILLISECONDS);
            } else {
                System.err.println("Max retries exceeded. Broker connection failed permanently.");
            }
        }
    }

    /**
     * Publishes an IndexRequest, attempting reconnection if needed.
     */
    public void publish(IndexRequest request) throws JMSException {
        ensureConnected();
        delegate.publish(request);
    }

    /**
     * Subscribes to indexing requests with automatic resubscription on reconnect.
     */
    public void subscribe(MessageHandler handler) throws JMSException {
        this.messageHandler = handler;
        ensureConnected();

        if (activeConsumer != null) {
            try {
                activeConsumer.close();
            } catch (JMSException ignored) {}
        }

        activeConsumer = delegate.createConsumer(message -> {
            try {
                IndexRequest request = BrokerClient.parseMessage(message);
                handler.handle(request);
            } catch (Exception e) {
                System.err.println("Error processing message: " + e.getMessage());
            }
        });
    }

    private void ensureConnected() throws JMSException {
        if (!delegate.isConnected()) {
            delegate.connect();
        }
    }

    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();

        if (activeConsumer != null) {
            try {
                activeConsumer.close();
            } catch (JMSException ignored) {}
        }

        delegate.close();
    }
}
