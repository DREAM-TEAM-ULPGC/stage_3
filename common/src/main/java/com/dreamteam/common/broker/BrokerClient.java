package com.dreamteam.common.broker;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.model.IndexRequest;
import com.google.gson.Gson;

/**
 * Client wrapper for Apache ActiveMQ message broker.
 * Provides simple publish/subscribe functionality for IndexRequest messages.
 */
public class BrokerClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final String brokerUrl;
    private final String queueName;
    private Connection connection;
    private Session session;
    private boolean connected = false;

    public BrokerClient() {
        this(ClusterConfig.getBrokerUrl(), ClusterConfig.getIndexingQueue());
    }

    public BrokerClient(String brokerUrl, String queueName) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
    }

    /**
     * Establishes connection to the message broker.
     */
    public synchronized void connect() throws JMSException {
        if (connected) return;

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

        String username = ClusterConfig.getBrokerUsername();
        String password = ClusterConfig.getBrokerPassword();
        if (username != null && password != null) {
            factory.setUserName(username);
            factory.setPassword(password);
        }

        connection = factory.createConnection();
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        connected = true;

        System.out.println("Connected to message broker at " + brokerUrl);
    }

    /**
     * Publishes an IndexRequest message to the queue.
     */
    public void publish(IndexRequest request) throws JMSException {
        ensureConnected();

        Queue queue = session.createQueue(queueName);
        try (MessageProducer producer = session.createProducer(queue)) {
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            String json = GSON.toJson(request);
            TextMessage message = session.createTextMessage(json);
            message.setStringProperty("idempotencyKey", request.getIdempotencyKey());
            message.setIntProperty("bookId", request.getBookId());
            message.setStringProperty("nodeId", request.getNodeId());

            producer.send(message);
            System.out.println("Published IndexRequest for book " + request.getBookId());
        }
    }

    /**
     * Creates a consumer for receiving IndexRequest messages.
     * The caller is responsible for processing messages and closing the consumer.
     */
    public MessageConsumer createConsumer(MessageListener listener) throws JMSException {
        ensureConnected();

        Queue queue = session.createQueue(queueName);
        MessageConsumer consumer = session.createConsumer(queue);
        consumer.setMessageListener(listener);

        System.out.println("Subscribed to queue: " + queueName);
        return consumer;
    }

    /**
     * Parses an IndexRequest from a JMS TextMessage.
     */
    public static IndexRequest parseMessage(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            String json = textMessage.getText();
            return GSON.fromJson(json, IndexRequest.class);
        }
        throw new JMSException("Expected TextMessage but got: " + message.getClass().getSimpleName());
    }

    private void ensureConnected() throws JMSException {
        if (!connected) {
            connect();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    @Override
    public synchronized void close() {
        try {
            if (session != null) session.close();
            if (connection != null) connection.close();
            connected = false;
            System.out.println("Disconnected from message broker");
        } catch (JMSException e) {
            System.err.println("Error closing broker connection: " + e.getMessage());
        }
    }
}
