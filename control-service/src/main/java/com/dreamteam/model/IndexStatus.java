package com.dreamteam.model;

public class IndexStatus {
    private final int booksIndexed;
    private final String lastUpdate;
    private final double indexSizeMB;

    public IndexStatus(int booksIndexed, String lastUpdate, double indexSizeMB) {
        this.booksIndexed = booksIndexed;
        this.lastUpdate = lastUpdate;
        this.indexSizeMB = indexSizeMB;
    }

    public int getBooksIndexed() { return booksIndexed; }
    public String getLastUpdate() { return lastUpdate; }
    public double getIndexSizeMB() { return indexSizeMB; }
}
