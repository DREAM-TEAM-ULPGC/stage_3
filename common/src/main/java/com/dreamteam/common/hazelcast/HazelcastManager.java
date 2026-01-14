package com.dreamteam.common.hazelcast;

import java.util.List;
import java.util.Map;

import com.dreamteam.common.config.ClusterConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

/**
 * Factory and utility class for Hazelcast distributed data structures.
 * Manages the Hazelcast instance lifecycle and provides access to distributed maps.
 */
public class HazelcastManager {

    private static volatile HazelcastInstance instance;
    private static final Object lock = new Object();

    /**
     * Gets or creates the Hazelcast instance for this node.
     * Uses TCP/IP discovery with members from configuration.
     */
    public static HazelcastInstance getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = createInstance();
                }
            }
        }
        return instance;
    }

    private static HazelcastInstance createInstance() {
        Config config = new Config();
        config.setClusterName(ClusterConfig.getHazelcastClusterName());

        // Network configuration
        NetworkConfig networkConfig = config.getNetworkConfig();
        networkConfig.setPort(ClusterConfig.getHazelcastPort());
        networkConfig.setPortAutoIncrement(true);

        // TCP/IP discovery (disable multicast for controlled environments)
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(true);

        List<String> members = ClusterConfig.getHazelcastMembers();
        for (String member : members) {
            joinConfig.getTcpIpConfig().addMember(member);
        }

        // Configure the inverted index map
        MapConfig indexMapConfig = new MapConfig(ClusterConfig.getIndexMapName());
        indexMapConfig.setBackupCount(ClusterConfig.getHazelcastBackupCount());
        indexMapConfig.setAsyncBackupCount(0);
        config.addMapConfig(indexMapConfig);

        // Configure map for tracking processed documents (idempotency)
        MapConfig processedMapConfig = new MapConfig("processed-documents");
        processedMapConfig.setBackupCount(ClusterConfig.getHazelcastBackupCount());
        config.addMapConfig(processedMapConfig);

        HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
        System.out.println("Hazelcast instance started. Cluster: " + config.getClusterName() +
                ", Members: " + hz.getCluster().getMembers().size());

        return hz;
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
