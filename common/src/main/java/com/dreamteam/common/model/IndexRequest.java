package com.dreamteam.common.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Message sent from Ingestion Service to Indexer Service via the message broker.
 * Contains all information needed to index a document.
 */
public class IndexRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int bookId;
    private final String nodeId;
    private final String datalakePath;
    private final String contentHash;
    private final long timestamp;

    public IndexRequest(int bookId, String nodeId, String datalakePath, String contentHash) {
        this.bookId = bookId;
        this.nodeId = nodeId;
        this.datalakePath = datalakePath;
        this.contentHash = contentHash;
        this.timestamp = Instant.now().toEpochMilli();
    }

    /** Unique book identifier from Gutenberg */
    public int getBookId() {
        return bookId;
    }

    /** ID of the node that ingested this document */
    public String getNodeId() {
        return nodeId;
    }

    /** Relative path within the datalake where the document is stored */
    public String getDatalakePath() {
        return datalakePath;
    }

    /** Hash of the document content for idempotency checks */
    public String getContentHash() {
        return contentHash;
    }

    /** Timestamp when this request was created */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Unique key for idempotency: combination of bookId and contentHash.
     * Used to detect duplicate indexing requests.
     */
    public String getIdempotencyKey() {
        return bookId + ":" + contentHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexRequest that = (IndexRequest) o;
        return bookId == that.bookId && Objects.equals(contentHash, that.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bookId, contentHash);
    }

    @Override
    public String toString() {
        return "IndexRequest{" +
                "bookId=" + bookId +
                ", nodeId='" + nodeId + '\'' +
                ", datalakePath='" + datalakePath + '\'' +
                ", contentHash='" + contentHash + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
