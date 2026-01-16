package com.dreamteam.common.datalake;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.dreamteam.common.model.ReplicationRequest;
import com.dreamteam.common.model.ReplicationResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * HTTP client for datalake replication between ingestion nodes.
 * Uses consistent hashing to distribute replicas evenly across all peers.
 * Sends POST /replicate to peer nodes.
 */
public class ReplicationClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final List<String> peerNodes;
    private final int replicationFactor;
    private final HttpClient httpClient;

    public ReplicationClient(List<String> peerNodes, int replicationFactor) {
        this.peerNodes = peerNodes == null ? List.of() : peerNodes.stream().filter(s -> s != null && !s.isBlank()).toList();
        this.replicationFactor = replicationFactor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Replicates to up to (replicationFactor - 1) peers.
     * Uses consistent hashing based on bookId to distribute replicas evenly.
     * This ensures all nodes receive approximately equal number of books.
     */
    public List<ReplicationResponse> replicate(ReplicationRequest request) {
        Objects.requireNonNull(request, "request");

        if (peerNodes.isEmpty() || replicationFactor <= 1) {
            return List.of();
        }

        int replicasToSend = Math.min(peerNodes.size(), Math.max(0, replicationFactor - 1));
        List<ReplicationResponse> responses = new ArrayList<>();

        // Use consistent hashing to select which peers get this book
        List<String> selectedPeers = selectPeersForBook(request.getBookId(), replicasToSend);

        for (String peer : selectedPeers) {
            ReplicationResponse resp = sendToPeer(peer, request);
            if (resp != null && !resp.isSuccess()) {
                System.err.printf("Replication failed: book=%d peer=%s reason=%s%n",
                        request.getBookId(), peer, resp.getMessage());
            }
            responses.add(resp);
        }

        return responses;
    }

    /**
     * Selects 'count' peers for a book using consistent hashing.
     * This distributes books evenly across all peers instead of always
     * sending to the first N peers.
     */
    private List<String> selectPeersForBook(int bookId, int count) {
        if (peerNodes.isEmpty() || count <= 0) {
            return List.of();
        }

        // Use bookId to determine starting position in the peer ring
        int startIndex = Math.abs(bookId) % peerNodes.size();
        
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count && i < peerNodes.size(); i++) {
            int index = (startIndex + i) % peerNodes.size();
            selected.add(peerNodes.get(index));
        }
        
        return selected;
    }

    private ReplicationResponse sendToPeer(String peerBaseUrl, ReplicationRequest request) {
        try {
            String base = peerBaseUrl.endsWith("/") ? peerBaseUrl.substring(0, peerBaseUrl.length() - 1) : peerBaseUrl;
            URI uri = URI.create(base + "/replicate");

            JsonObject payload = new JsonObject();
            payload.addProperty("bookId", request.getBookId());
            payload.addProperty("sourceNodeId", request.getSourceNodeId());
            payload.addProperty("relativePath", request.getRelativePath());
            payload.addProperty("contentHash", request.getContentHash());

            payload.addProperty("rawContent", Base64.getEncoder().encodeToString(request.getRawContent()));
            payload.addProperty("headerContent", Base64.getEncoder().encodeToString(request.getHeaderContent()));
            payload.addProperty("bodyContent", Base64.getEncoder().encodeToString(request.getBodyContent()));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                ReplicationResponse parsed = GSON.fromJson(httpResponse.body(), ReplicationResponse.class);
                if (parsed != null) return parsed;
                return ReplicationResponse.success(peerBaseUrl, request.getBookId());
            }

            return ReplicationResponse.failure(peerBaseUrl, request.getBookId(),
                    "HTTP " + httpResponse.statusCode() + ": " + truncate(httpResponse.body(), 200));

        } catch (IOException | InterruptedException | RuntimeException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = e.getClass().getSimpleName();
            } else {
                msg = e.getClass().getSimpleName() + ": " + msg;
            }
            return ReplicationResponse.failure(peerBaseUrl, request.getBookId(), "Request failed: " + msg);
        }
    }

    public List<String> getPeerNodes() {
        return Collections.unmodifiableList(peerNodes);
    }

    /**
     * Returns peers that respond to GET /status with HTTP 200.
     */
    public List<String> getHealthyPeers() {
        if (peerNodes.isEmpty()) return List.of();

        List<String> healthy = new ArrayList<>();
        for (String peer : peerNodes) {
            try {
                String base = peer.endsWith("/") ? peer.substring(0, peer.length() - 1) : peer;
                URI uri = URI.create(base + "/status");
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) healthy.add(peer);
            } catch (IOException | InterruptedException | RuntimeException ignore) {
            }
        }
        return healthy;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    @Override
    public void close() {
        // HttpClient has no close
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
