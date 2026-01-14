package com.dreamteam.model;

import java.util.List;
import java.util.Map;

public class SearchResult {
    private final String query;
    private final Map<String, String> filters;
    private final int count;
    private final List<Map<String, Object>> results;

    public SearchResult(String query, Map<String, String> filters,
                        int count, List<Map<String, Object>> results) {
        this.query = query;
        this.filters = filters;
        this.count = count;
        this.results = results;
    }

    public String getQuery() { return query; }
    public Map<String, String> getFilters() { return filters; }
    public int getCount() { return count; }
    public List<Map<String, Object>> getResults() { return results; }
}
