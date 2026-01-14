package com.dreamteam.model;

public class IngestionStatus {
    private final int bookId;
    private final String status;
    private final String path;

    public IngestionStatus(int bookId, String status, String path) {
        this.bookId = bookId;
        this.status = status;
        this.path = path;
    }

    public int getBookId() { return bookId; }
    public String getStatus() { return status; }
    public String getPath() { return path; }
}
