package com.dreamteam.search.distributed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dreamteam.common.config.ClusterConfig;
import com.dreamteam.common.hazelcast.DistributedIndex;
import com.dreamteam.common.hazelcast.HazelcastManager;
import com.dreamteam.common.hazelcast.PostingEntry;

/**
 * Search engine that queries the distributed Hazelcast index.
 * Provides real-time search across all indexed documents in the cluster.
 */
public class DistributedSearchEngine {

    private final DistributedIndex distributedIndex;
    private final String nodeId;

    public DistributedSearchEngine() {
        this.distributedIndex = new DistributedIndex();
        this.nodeId = ClusterConfig.getNodeId();
    }

    /**
     * Searches the distributed index.
     *
     * @param rawQuery the raw query string
     * @param mode     "and" for intersection, "or" for union
     * @param limit    maximum results to return
     * @return list of search results
     */
    public List<SearchResult> search(String rawQuery, String mode, int limit) {
        long startTime = System.currentTimeMillis();

        List<String> terms = tokenize(rawQuery);
        if (terms.isEmpty()) {
            return List.of();
        }

        // Search in distributed index
        Map<Integer, Double> scores = distributedIndex.search(terms, mode);

        // Sort by score descending, then by bookId ascending
        List<SearchResult> results = scores.entrySet().stream()
                .map(e -> new SearchResult(e.getKey(), e.getValue()))
                .sorted(Comparator
                        .comparingDouble(SearchResult::score).reversed()
                        .thenComparingInt(SearchResult::bookId))
                .limit(limit)
                .toList();

        long elapsed = System.currentTimeMillis() - startTime;

        System.out.printf("[%s] Search '%s' (%s): %d results in %dms%n",
                nodeId, rawQuery, mode, results.size(), elapsed);

        return results;
    }

    /**
     * Gets term statistics for a query (useful for debugging).
     */
    public List<TermStats> getTermStats(String rawQuery) {
        List<String> terms = tokenize(rawQuery);
        List<TermStats> stats = new ArrayList<>();

        long totalDocs = distributedIndex.getTotalDocuments();

        for (String term : terms) {
            List<PostingEntry> postings = distributedIndex.getPostings(term);
            int df = postings.size();
            double idf = Math.log((totalDocs + 1.0) / (df + 1.0)) + 1.0;

            stats.add(new TermStats(term, df, idf));
        }

        return stats;
    }

    /**
     * Gets index statistics.
     */
    public Map<String, Object> getIndexStats() {
        return distributedIndex.getStats();
    }

    /**
     * Checks if the search engine is connected to Hazelcast.
     */
    public boolean isConnected() {
        return HazelcastManager.isRunning();
    }

    /**
     * Gets the number of nodes in the cluster.
     */
    public int getClusterSize() {
        return HazelcastManager.getClusterSize();
    }

    private List<String> tokenize(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    // Result records
    public record SearchResult(int bookId, double score) {}

    public record TermStats(String term, int documentFrequency, double idf) {}
}
