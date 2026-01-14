package com.dreamteam.model;

import java.time.LocalDateTime;

public class ControlRecord {
    private int bookId;
    private String stage;
    private String status;
    private String error;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public ControlRecord(int bookId) {
        this.bookId = bookId;
        this.stage = "created";
        this.status = "pending";
        this.startTime = LocalDateTime.now();
    }

    public ControlRecord(int bookId, String status, String error) {
        this(bookId);
        this.status = status;
        this.error = error;
    }

    public int getBookId() { return bookId; }
    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }

    public void setStage(String stage) { this.stage = stage; }
    public void setStatus(String status) { this.status = status; }
    public void setError(String error) { this.error = error; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
