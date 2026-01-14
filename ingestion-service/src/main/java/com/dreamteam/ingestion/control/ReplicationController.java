package com.dreamteam.ingestion.control;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.datalake.DatalakeStats;
import com.dreamteam.common.datalake.ReplicationHandler;
import com.dreamteam.common.model.ReplicationResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;

/**
 * Controller for handling replication requests from peer nodes.
 */
public class ReplicationController {

    private static final Gson GSON = new Gson();

    private final ReplicationHandler replicationHandler;
    private final DatalakeStats datalakeStats;

    public ReplicationController() {
        this.replicationHandler = new ReplicationHandler();
        this.datalakeStats = new DatalakeStats();
    }

    public ReplicationController(String datalakeDir) {
        this.replicationHandler = new ReplicationHandler(datalakeDir);
        this.datalakeStats = new DatalakeStats(datalakeDir);
    }

    /**
     * POST /replicate
     * Receives replication request from a peer node.
     */
    public void handleReplication(Context ctx) {
        try {
            JsonObject payload = GSON.fromJson(ctx.body(), JsonObject.class);

            int bookId = payload.get("bookId").getAsInt();
            String sourceNodeId = payload.get("sourceNodeId").getAsString();
            String relativePath = payload.get("relativePath").getAsString();
            String rawContentB64 = payload.get("rawContent").getAsString();
            String headerB64 = payload.get("headerContent").getAsString();
            String bodyB64 = payload.get("bodyContent").getAsString();
            String contentHash = payload.get("contentHash").getAsString();

            System.out.printf("Received replication request: book=%d from node=%s%n",
                    bookId, sourceNodeId);

            ReplicationResponse response = replicationHandler.handleReplication(
                    bookId, sourceNodeId, relativePath,
                    rawContentB64, headerB64, bodyB64, contentHash
            );

            if (response.isSuccess()) {
                ctx.status(201).json(response);
            } else {
                ctx.status(500).json(response);
            }

        } catch (Exception e) {
            System.err.println("Replication error: " + e.getMessage());
            ctx.status(400).json(ReplicationResponse.failure(
                    ClusterConfig.getNodeId(), -1, "Invalid request: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /datalake/stats
     * Returns statistics about the local datalake partition.
     */
    public void getDatalakeStats(Context ctx) {
        ctx.json(datalakeStats.getStats());
    }

    /**
     * GET /datalake/book/{book_id}/status
     * Returns replication status for a specific book.
     */
    public void getBookStatus(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("book_id"));
            ctx.json(datalakeStats.getBookReplicationStatus(bookId));
        } catch (NumberFormatException e) {
            ctx.status(400).json(java.util.Map.of("error", "Invalid book_id"));
        }
    }
}
