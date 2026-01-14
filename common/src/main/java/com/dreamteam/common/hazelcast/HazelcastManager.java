package com.dreamteam.common.hazelcast;

import java.util.List;
import java.util.Map;

import com.dreamteam.common.config.ClusterConfig;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

/**
 * Factory and utility class for Hazelcast distributed data structures.
 * Manages the Hazelcast CLIENT instance lifecycle and provides access to distributed maps.
 * Connects to external Hazelcast cluster nodes.
 */
public class HazelcastManager {

    private static volatile HazelcastInstance instance;
    private static final Object lock = new Object();

    /**
     * Gets or creates the Hazelcast CLIENT instance.
     * Connects to the Hazelcast cluster members from configuration.
     */
    public static HazelcastInstance getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = createClientInstance();
                }
            }
        }
        return instance;
    }

    private static HazelcastInstance createClientInstance() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName(ClusterConfig.getHazelcastClusterName());

        // Add cluster member addresses
        List<String> members = ClusterConfig.getHazelcastMembers();
        for (String member : members) {
            clientConfig.getNetworkConfig().addAddress(member);
        }

        // Connection retry configuration
        clientConfig.getConnectionStrategyConfig()
            .getConnectionRetryConfig()
            .setClusterConnectTimeoutMillis(30000);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);
        System.out.println("Hazelcast CLIENT connected. Cluster: " + clientConfig.getClusterName() +
                ", Members: " + client.getCluster().getMembers().size());

        return client;
    }

    /**
     * Gets the distributed inverted index map.
     * Structure: term -> (bookId -> list of positions)
     */
    public static IMap<String, Map<Integer, List<Integer>>> getInvertedIndex() {
        return getInstance().getMap(ClusterConfig.getIndexMapName());
    }

    /**
     * Gets the map for tracking processed document hashes (for idempotency).
     * Key: idempotencyKey (bookId:contentHash), Value: timestamp when processed
     */
    public static IMap<String, Long> getProcessedDocuments() {
        return getInstance().getMap("processed-documents");
    }

    /**
     * Shuts down the Hazelcast instance gracefully.
     */
    public static void shutdown() {
        synchronized (lock) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
                System.out.println("Hazelcast instance shut down");
            }
        }
    }

    /**
     * Checks if the Hazelcast instance is running.
     */
    public static boolean isRunning() {
        return instance != null && instance.getLifecycleService().isRunning();
    }

    /**
     * Gets the number of members in the cluster.
     */
    public static int getClusterSize() {
        if (!isRunning()) return 0;
        return instance.getCluster().getMembers().size();
    }
}
