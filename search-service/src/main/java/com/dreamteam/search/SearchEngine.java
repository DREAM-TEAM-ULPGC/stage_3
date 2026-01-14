package com.dreamteam.search;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;


public class SearchEngine {

    private Map<String, List<Integer>> index;
    private Map<String, Double> idf;
    private int N;

    public SearchEngine(Path indexPath) {
        this.index = loadIndex(indexPath);
        this.N = estimateDocCount(index);
        this.idf = computeIdf(index, N);
    }

    public void reload(Path indexPath) {
        this.index = loadIndex(indexPath);
        this.N = estimateDocCount(index);
        this.idf = computeIdf(index, N);
    }

    public static class ScoredDoc {
        public final int bookId;
        public final double score;
        public ScoredDoc(int bookId, double score) {
            this.bookId = bookId; this.score = score;
        }
    }

    public List<ScoredDoc> search(String rawQuery, String mode) {
        List<String> terms = tokenize(rawQuery);
        if (terms.isEmpty()) return List.of();

        Set<Integer> candidateDocs;
        if ("or".equalsIgnoreCase(mode)) {
            candidateDocs = new HashSet<>();
            for (String t : terms) candidateDocs.addAll(index.getOrDefault(t, List.of()));
        } else {
            candidateDocs = null;
            for (String term : terms) {
                List<Integer> postings = index.getOrDefault(term, List.of());
                if (candidateDocs == null) candidateDocs = new HashSet<>(postings);
                else candidateDocs.retainAll(postings);
                if (candidateDocs.isEmpty()) break;
            }
            if (candidateDocs == null) candidateDocs = Set.of();
        }

        List<ScoredDoc> scored = new ArrayList<>();
        for (int docId : candidateDocs) {
            double s = 0.0;
            for (String term : terms) {
                List<Integer> postings = index.get(term);
                if (postings != null && postings.contains(docId)) {
                    s += idf.getOrDefault(term, 0.0);
                }
            }
            scored.add(new ScoredDoc(docId, s));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble((ScoredDoc doc) -> doc.score).reversed()
                        .thenComparingInt(doc -> doc.bookId))
                .collect(Collectors.toList());
    }

    private static List<String> tokenize(String q) {
        return Arrays.stream(q.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(s -> !s.isBlank()).toList();
    }

    private static Map<String, List<Integer>> loadIndex(Path path) {
        try {
            // Check if path is a directory (TSV index) or file (JSON index)
            if (Files.isDirectory(path)) {
                return loadTsvIndex(path);
            } else if (Files.exists(path)) {
                return loadJsonIndex(path);
            } else {
                // Try TSV index directory
                Path tsvPath = path.getParent().resolve("tsv-index");
                if (Files.isDirectory(tsvPath)) {
                    return loadTsvIndex(tsvPath);
                }
                System.out.println("Warning: Index not found at " + path + ". Starting with empty index.");
                return new HashMap<>();
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to load inverted index: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private static Map<String, List<Integer>> loadJsonIndex(Path path) throws IOException {
        String json = Files.readString(path);
        Type type = new TypeToken<Map<String, List<Integer>>>(){}.getType();
        Map<String, List<Integer>> map = new Gson().fromJson(json, type);
        
        if (map == null) {
            System.out.println("Warning: Index file is empty or invalid at " + path + ". Starting with empty index.");
            return new HashMap<>();
        }
        
        map.replaceAll((k, v) -> v.stream().distinct().collect(Collectors.toList()));
        System.out.println("Successfully loaded JSON index from " + path + " with " + map.size() + " terms.");
        return map;
    }

    private static Map<String, List<Integer>> loadTsvIndex(Path tsvDir) throws IOException {
        Map<String, List<Integer>> index = new HashMap<>();
        
        try (var files = Files.list(tsvDir)) {
            files.filter(f -> f.toString().endsWith(".tsv"))
                 .forEach(file -> {
                     try {
                         String term = file.getFileName().toString().replace(".tsv", "");
                         Set<Integer> bookIds = new HashSet<>();
                         
                         for (String line : Files.readAllLines(file)) {
                             String[] parts = line.split("\t");
                             if (parts.length >= 1) {
                                 try {
                                     bookIds.add(Integer.parseInt(parts[0].trim()));
                                 } catch (NumberFormatException ignore) {}
                             }
                         }
                         
                         if (!bookIds.isEmpty()) {
                             index.put(term, new ArrayList<>(bookIds));
                         }
                     } catch (IOException e) {
                         System.err.println("Error reading TSV file: " + file);
                     }
                 });
        }
        
        System.out.println("Successfully loaded TSV index from " + tsvDir + " with " + index.size() + " terms.");
        return index;
    }

    private static int estimateDocCount(Map<String, List<Integer>> idx) {
        Set<Integer> docs = new HashSet<>();
        for (var v : idx.values()) docs.addAll(v);
        return docs.size();
    }

    private static Map<String, Double> computeIdf(Map<String, List<Integer>> idx, int N) {
        Map<String, Double> map = new HashMap<>();
        for (var e : idx.entrySet()) {
            int df = new HashSet<>(e.getValue()).size();
            double idf = Math.log((N + 1.0) / (df + 1.0)) + 1.0;
            map.put(e.getKey(), idf);
        }
        return map;
    }
}
