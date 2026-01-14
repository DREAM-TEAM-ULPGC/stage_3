package com.dreamteam.utils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.dreamteam.model.ControlRecord;

public class Orchestrator {
    private final ServiceClient client = new ServiceClient();
    private final LogManager logManager = new LogManager();
    private final Map<Integer, ControlRecord> bookRecords = new ConcurrentHashMap<>();

    
    public Map<String, Object> runPipeline(int bookId) {
        Map<String, Object> response = new LinkedHashMap<>();
        ControlRecord record = new ControlRecord(bookId);

        try {
            record.setStage("ingestion");
            logManager.log("Starting ingestion for book " + bookId);
            client.post("http://localhost:7001/ingest/" + bookId);

            if (!client.waitUntilAvailable("http://localhost:7001/ingest/status/" + bookId)) {
                throw new RuntimeException("Ingestion timed out");
            }

            record.setStage("indexing");
            logManager.log("Ingestion done. Starting indexing for book " + bookId);
            client.post("http://localhost:7002/index/update/" + bookId);

            record.setStage("search-refresh");
            logManager.log("Indexing done. Notifying search service to refresh cache.");
            client.post("http://localhost:7003/admin/reload");

            record.setStage("completed");
            record.setEndTime(LocalDateTime.now());
            record.setStatus("success");
            logManager.log("Pipeline for book " + bookId + " completed successfully.");
        } catch (Exception exception) {
            record.setStage("error");
            record.setStatus("failed");
            record.setError(exception.getMessage());
            logManager.log("Error while processing book " + bookId + ": " + exception.getMessage());
        }

        bookRecords.put(bookId, record);
        response.put("book_id", bookId);
        response.put("status", record.getStatus());
        response.put("stage", record.getStage());
        return response;
    }

    public ControlRecord getBookStatus(int bookId) {
        return bookRecords.getOrDefault(bookId,
                new ControlRecord(bookId, "unknown", "Book not found"));
    }

    public List<ControlRecord> getAllRecords() {
        return new ArrayList<>(bookRecords.values());
    }
}
