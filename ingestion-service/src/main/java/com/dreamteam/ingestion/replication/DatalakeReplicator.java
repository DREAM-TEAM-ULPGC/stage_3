package com.dreamteam.ingestion.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.datalake.ReplicationClient;
import com.dreamteam.common.model.ReplicationRequest;
import com.dreamteam.common.model.ReplicationResponse;
import com.dreamteam.common.util.HashUtil;

/**
 * Service for replicating ingested content to peer nodes.
 * Called after successful ingestion to ensure fault tolerance.
 */
public class DatalakeReplicator implements AutoCloseable {

    private final ReplicationClient replicationClient;
    private final String nodeId;
    private final boolean enabled;

    public DatalakeReplicator() {
        List<String> peers = ClusterConfig.getDatalakePeers();
        int factor = ClusterConfig.getReplicationFactor();

        this.replicationClient = new ReplicationClient(peers, factor);
        this.nodeId = ClusterConfig.getNodeId();
        this.enabled = !peers.isEmpty() && factor > 1;

        if (enabled) {
            System.out.printf("DatalakeReplicator enabled: peers=%s, factor=%d%n", peers, factor);
        } else {
            System.out.println("DatalakeReplicator disabled: no peers configured or factor <= 1");
        }
    }

    /**
     * Replicates a newly ingested book to peer nodes.
     *
     * @param bookId       the book ID
     * @param relativePath relative path within datalake
     * @param bookDir      absolute path to the book directory
     * @return number of successful replications
     */
    public int replicateBook(int bookId, String relativePath, Path bookDir) {
        if (!enabled) {
            return 0;
        }

        try {
            // Read content files
            byte[] rawContent = Files.readAllBytes(bookDir.resolve("raw.txt"));
            byte[] headerContent = Files.readAllBytes(bookDir.resolve("header.txt"));
            byte[] bodyContent = Files.readAllBytes(bookDir.resolve("body.txt"));

            String contentHash = HashUtil.sha256(rawContent);

            ReplicationRequest request = new ReplicationRequest(
                    bookId,
                    nodeId,
                    relativePath,
                    rawContent,
                    headerContent,
                    bodyContent,
                    contentHash
            );

            List<ReplicationResponse> responses = replicationClient.replicate(request);

            long successCount = responses.stream()
                    .filter(ReplicationResponse::isSuccess)
                    .count();

            System.out.printf("Book %d replicated to %d peer(s)%n", bookId, successCount);

            return (int) successCount;

        } catch (IOException e) {
            System.err.printf("Failed to replicate book %d: %s%n", bookId, e.getMessage());
            return 0;
        }
    }

    /**
     * Replicates content directly without reading from disk.
     */
    public int replicateBook(int bookId, String relativePath,
                              String rawContent, String headerContent, String bodyContent) {
        if (!enabled) {
            return 0;
        }

        String contentHash = HashUtil.sha256(rawContent);

        ReplicationRequest request = new ReplicationRequest(
                bookId,
                nodeId,
                relativePath,
                rawContent.getBytes(StandardCharsets.UTF_8),
                headerContent.getBytes(StandardCharsets.UTF_8),
                bodyContent.getBytes(StandardCharsets.UTF_8),
                contentHash
        );

        List<ReplicationResponse> responses = replicationClient.replicate(request);

        return (int) responses.stream()
                .filter(ReplicationResponse::isSuccess)
                .count();
    }

    /**
     * Returns the list of configured peer nodes.
     */
    public List<String> getPeerNodes() {
        return replicationClient.getPeerNodes();
    }

    /**
     * Returns list of currently healthy peers.
     */
    public List<String> getHealthyPeers() {
        return replicationClient.getHealthyPeers();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getReplicationFactor() {
        return replicationClient.getReplicationFactor();
    }

    @Override
    public void close() {
        replicationClient.close();
    }
}
