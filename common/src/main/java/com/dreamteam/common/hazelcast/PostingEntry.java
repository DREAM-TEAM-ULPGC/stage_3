package com.dreamteam.common.hazelcast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a posting list entry for a term in the inverted index.
 * Stores book ID and list of positions where the term appears.
 */
public class PostingEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int bookId;
    private final List<Integer> positions;

    public PostingEntry(int bookId, List<Integer> positions) {
        this.bookId = bookId;
        this.positions = new ArrayList<>(positions);
    }

    public int getBookId() {
        return bookId;
    }

    public List<Integer> getPositions() {
        return positions;
    }

    public int getTermFrequency() {
        return positions.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PostingEntry that = (PostingEntry) o;
        return bookId == that.bookId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bookId);
    }

    @Override
    public String toString() {
        return "PostingEntry{bookId=" + bookId + ", tf=" + positions.size() + "}";
    }
}
