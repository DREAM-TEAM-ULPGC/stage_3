package com.dreamteam.common.broker;

/**
 * Constants for message broker queue/topic names.
 * Centralized to ensure consistency across services.
 */
public final class BrokerTopics {

    private BrokerTopics() {}

    /** Queue for indexing requests (ingestion -> indexer) */
    public static final String INDEXING_REQUESTS = "indexing-requests";

    /** Queue for replication requests (node -> node) */
    public static final String REPLICATION_REQUESTS = "replication-requests";

    /** Topic for cluster status updates */
    public static final String CLUSTER_STATUS = "cluster-status";

    /** Queue for search requests (for future load balancing) */
    public static final String SEARCH_REQUESTS = "search-requests";

    /** Dead letter queue for failed messages */
    public static final String DEAD_LETTER = "dead-letter-queue";
}
