package com.dreamteam.common.model;

import java.io.Serializable;

/**
 * Response from a replication request.
 */
public class ReplicationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String nodeId;
    private final int bookId;
    private final String message;

    public ReplicationResponse(boolean success, String nodeId, int bookId, String message) {
        this.success = success;
        this.nodeId = nodeId;
        this.bookId = bookId;
        this.message = message;
    }

    public static ReplicationResponse success(String nodeId, int bookId) {
        return new ReplicationResponse(true, nodeId, bookId, "Replicated successfully");
    }

    public static ReplicationResponse failure(String nodeId, int bookId, String reason) {
        return new ReplicationResponse(false, nodeId, bookId, reason);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getBookId() {
        return bookId;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "ReplicationResponse{" +
                "success=" + success +
                ", nodeId='" + nodeId + '\'' +
                ", bookId=" + bookId +
                ", message='" + message + '\'' +
                '}';
    }
}
