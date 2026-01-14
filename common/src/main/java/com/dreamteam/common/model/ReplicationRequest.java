package com.dreamteam.common.model;

import java.io.Serializable;

/**
 * Message sent between datalake nodes for document replication.
 */
public class ReplicationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int bookId;
    private final String sourceNodeId;
    private final String relativePath;
    private final byte[] rawContent;
    private final byte[] headerContent;
    private final byte[] bodyContent;
    private final String contentHash;

    public ReplicationRequest(int bookId, String sourceNodeId, String relativePath,
                               byte[] rawContent, byte[] headerContent, byte[] bodyContent,
                               String contentHash) {
        this.bookId = bookId;
        this.sourceNodeId = sourceNodeId;
        this.relativePath = relativePath;
        this.rawContent = rawContent;
        this.headerContent = headerContent;
        this.bodyContent = bodyContent;
        this.contentHash = contentHash;
    }

    public int getBookId() {
        return bookId;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public byte[] getRawContent() {
        return rawContent;
    }

    public byte[] getHeaderContent() {
        return headerContent;
    }

    public byte[] getBodyContent() {
        return bodyContent;
    }

    public String getContentHash() {
        return contentHash;
    }

    @Override
    public String toString() {
        return "ReplicationRequest{" +
                "bookId=" + bookId +
                ", sourceNodeId='" + sourceNodeId + '\'' +
                ", relativePath='" + relativePath + '\'' +
                ", contentHash='" + contentHash + '\'' +
                ", rawSize=" + (rawContent != null ? rawContent.length : 0) +
                '}';
    }
}
