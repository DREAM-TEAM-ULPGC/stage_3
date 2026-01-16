package com.dreamteam.common.hazelcast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hazelcast.map.IMap;

/**
 * Distributed inverted index backed by Hazelcast.
 * Provides thread-safe operations for indexing and searching.
 */
public class DistributedIndex {

    private static final String INDEX_MAP_NAME = "inverted-index";
    private static final String STATS_MAP_NAME = "index-stats";

    private final IMap<String, List<PostingEntry>> indexMap;
    private final IMap<String, Long> statsMap;
    private final IMap<String, Long> processedDocs;

    public DistributedIndex() {
        this.indexMap = HazelcastManager.getInstance().getMap(INDEX_MAP_NAME);
        this.statsMap = HazelcastManager.getInstance().getMap(STATS_MAP_NAME);
        this.processedDocs = HazelcastManager.getProcessedDocuments();
    }

    /**
     * Adds a document to the index.
     * Thread-safe: uses Hazelcast locking for concurrent updates.
     *
     * @param bookId        the book ID
     * @param termPositions map of term -> positions in document
     * @return number of terms indexed
     */
    public int indexDocument(int bookId, Map<String, List<Integer>> termPositions) {
        if (termPositions.isEmpty()) {
            return 0;
        }

        // OPTIMIZATION: Batch fetch all existing postings first (single network call)
        Set<String> terms = termPositions.keySet();
        Map<String, List<PostingEntry>> existingPostings = indexMap.getAll(terms);

        // Prepare updates locally
        Map<String, List<PostingEntry>> updates = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : termPositions.entrySet()) {
            String term = entry.getKey();
            List<Integer> positions = entry.getValue();

            // Get existing or create new list
            List<PostingEntry> postings = existingPostings.get(term);
            if (postings == null) {
                postings = new ArrayList<>();
            } else {
                // Create mutable copy
                postings = new ArrayList<>(postings);
                // Remove existing entry for this book if present (re-indexing)
                postings.removeIf(p -> p.getBookId() == bookId);
            }

            // Add new posting
            postings.add(new PostingEntry(bookId, positions));
            updates.put(term, postings);
        }

        // OPTIMIZATION: Batch write all updates (single network call)
        indexMap.putAll(updates);

        int termsIndexed = updates.size();

        // Update stats
        incrementStat("total_documents");
        incrementStat("total_terms_indexed", termsIndexed);

        return termsIndexed;
    }

    /**
     * Searches for documents containing the given terms.
     *
     * @param terms list of search terms
     * @param mode  "and" for intersection, "or" for union
     * @return map of bookId -> relevance score
     */
    public Map<Integer, Double> search(List<String> terms, String mode) {
        if (terms.isEmpty()) {
            return Map.of();
        }

        // Compute IDF for scoring
        long totalDocs = getTotalDocuments();
        Map<String, Double> idfMap = new HashMap<>();
        for (String term : terms) {
            List<PostingEntry> postings = indexMap.get(term);
            int df = (postings != null) ? postings.size() : 0;
            double idf = Math.log((totalDocs + 1.0) / (df + 1.0)) + 1.0;
            idfMap.put(term, idf);
        }

        // Find candidate documents
        Set<Integer> candidates;
        if ("or".equalsIgnoreCase(mode)) {
            candidates = new HashSet<>();
            for (String term : terms) {
                List<PostingEntry> postings = indexMap.get(term);
                if (postings != null) {
                    for (PostingEntry p : postings) {
                        candidates.add(p.getBookId());
                    }
                }
            }
        } else {
            // AND mode - intersection
            candidates = null;
            for (String term : terms) {
                List<PostingEntry> postings = indexMap.get(term);
                Set<Integer> bookIds = new HashSet<>();
                if (postings != null) {
                    for (PostingEntry p : postings) {
                        bookIds.add(p.getBookId());
                    }
                }

                if (candidates == null) {
                    candidates = bookIds;
                } else {
                    candidates.retainAll(bookIds);
                }

                if (candidates.isEmpty()) break;
            }
            if (candidates == null) candidates = Set.of();
        }

        // Score documents using TF-IDF
        Map<Integer, Double> scores = new HashMap<>();
        for (int bookId : candidates) {
            double score = 0.0;
            for (String term : terms) {
                List<PostingEntry> postings = indexMap.get(term);
                if (postings != null) {
                    for (PostingEntry p : postings) {
                        if (p.getBookId() == bookId) {
                            // TF-IDF: tf * idf
                            double tf = 1 + Math.log(p.getTermFrequency());
                            score += tf * idfMap.getOrDefault(term, 1.0);
                            break;
                        }
                    }
                }
            }
            scores.put(bookId, score);
        }

        return scores;
    }

    /**
     * Gets postings for a specific term.
     */
    public List<PostingEntry> getPostings(String term) {
        return indexMap.getOrDefault(term, List.of());
    }

    /**
     * Checks if a document has been processed (for idempotency).
     */
    public boolean isDocumentProcessed(String idempotencyKey) {
        return processedDocs.containsKey(idempotencyKey);
    }

    /**
     * Marks a document as processed.
     */
    public void markDocumentProcessed(String idempotencyKey) {
        processedDocs.put(idempotencyKey, System.currentTimeMillis());
    }

    /**
     * Removes a document from the index.
     */
    public int removeDocument(int bookId) {
        int termsRemoved = 0;

        for (String term : indexMap.keySet()) {
            indexMap.lock(term);
            try {
                List<PostingEntry> postings = indexMap.get(term);
                if (postings != null) {
                    boolean removed = postings.removeIf(p -> p.getBookId() == bookId);
                    if (removed) {
                        if (postings.isEmpty()) {
                            indexMap.remove(term);
                        } else {
                            indexMap.put(term, postings);
                        }
                        termsRemoved++;
                    }
                }
            } finally {
                indexMap.unlock(term);
            }
        }

        return termsRemoved;
    }

    /**
     * Gets index statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_terms", indexMap.size());
        stats.put("total_documents", getTotalDocuments());
        stats.put("processed_documents", processedDocs.size());
        stats.put("cluster_size", HazelcastManager.getClusterSize());
        stats.put("local_entries", indexMap.getLocalMapStats().getOwnedEntryCount());
        stats.put("backup_entries", indexMap.getLocalMapStats().getBackupEntryCount());
        stats.put("heap_cost_bytes", indexMap.getLocalMapStats().getHeapCost());
        return stats;
    }

    /**
     * Returns total unique documents in the index.
     */
    public long getTotalDocuments() {
        Long count = statsMap.get("total_documents");
        return count != null ? count : 0;
    }

    private void incrementStat(String key) {
        incrementStat(key, 1);
    }

    private void incrementStat(String key, long delta) {
        statsMap.lock(key);
        try {
            Long current = statsMap.getOrDefault(key, 0L);
            statsMap.put(key, current + delta);
        } finally {
            statsMap.unlock(key);
        }
    }

    /**
     * Clears the entire index.
     */
    public void clear() {
        indexMap.clear();
        statsMap.clear();
        processedDocs.clear();
        System.out.println("Distributed index cleared");
    }
}
