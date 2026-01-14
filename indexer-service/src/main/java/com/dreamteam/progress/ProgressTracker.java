package com.dreamteam.progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


public class ProgressTracker {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private String lastDay;
    private String lastHour;
    private int lastIndexedId;

    public ProgressTracker() {
        this.lastDay = null;
        this.lastHour = null;
        this.lastIndexedId = -1;
    }

    public ProgressTracker(String lastDay, String lastHour, int lastIndexedId) {
        this.lastDay = lastDay;
        this.lastHour = lastHour;
        this.lastIndexedId = lastIndexedId;
    }

    public static ProgressTracker load(String progressPath) {
        Path path = Paths.get(progressPath);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                return gson.fromJson(json, ProgressTracker.class);
            } catch (IOException exception) {
                System.err.println("Failed to load progress: " + exception.getMessage());
            }
        }
        return new ProgressTracker();
    }

    public void save(String progressPath) {
        Path path = Paths.get(progressPath);
        try {
            Files.createDirectories(path.getParent());
            String json = gson.toJson(this);
            Files.writeString(path, json);
        } catch (IOException exception) {
            System.err.println("Failed to save progress: " + exception.getMessage());
        }
    }

    public String getLastDay() {
        return lastDay;
    }

    public void setLastDay(String lastDay) {
        this.lastDay = lastDay;
    }

    public String getLastHour() {
        return lastHour;
    }

    public void setLastHour(String lastHour) {
        this.lastHour = lastHour;
    }

    public int getLastIndexedId() {
        return lastIndexedId;
    }

    public void setLastIndexedId(int lastIndexedId) {
        this.lastIndexedId = lastIndexedId;
    }

    @Override
    public String toString() {
        return String.format("Progress{day=%s, hour=%s, id=%d}", lastDay, lastHour, lastIndexedId);
    }
}