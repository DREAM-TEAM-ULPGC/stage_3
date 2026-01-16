package com.dreamteam.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.dreamteam.progress.ProgressTracker;

public class InvertedIndexer {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-záéíóúüñ]+\\b");
    
    // Batch size: number of books to process before flushing to disk
    private static final int BATCH_SIZE = 50;

    private final String datalakePath;
    private final String outputDir;
    private final String progressPath;
    
    // Batch buffer: term -> list of posting lines to write
    private final Map<String, List<String>> batchBuffer = new ConcurrentHashMap<>();
    private int booksInBatch = 0;

    public InvertedIndexer(String datalakePath, String outputDir, String progressPath) {
        this.datalakePath = datalakePath;
        this.outputDir = outputDir;
        this.progressPath = progressPath;
    }

    public void buildIndex() throws IOException {

        Path datalake = Paths.get(datalakePath);
        Path outputDirectory = Paths.get(outputDir);

        Files.createDirectories(outputDirectory);

        ProgressTracker progress = ProgressTracker.load(progressPath);
        System.out.println("Last progress: " + progress);

        List<Path> dayFolders = Files.list(datalake)
                .filter(Files::isDirectory)
                .sorted()
                .collect(Collectors.toList());

        for (Path dayFolder : dayFolders) {

            String dayName = dayFolder.getFileName().toString();

            if (progress.getLastDay() != null && dayName.compareTo(progress.getLastDay()) < 0) {
                continue;
            }

            List<Path> hourFolders = Files.list(dayFolder)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(p -> {
                        String name = p.getFileName().toString();
                        return name.matches("\\d+") ? Integer.parseInt(name) : 0;
                    }))
                    .collect(Collectors.toList());

            for (Path hourFolder : hourFolders) {

                String hourName = hourFolder.getFileName().toString();

                if (dayName.equals(progress.getLastDay()) && progress.getLastHour() != null) {
                    if (hourName.matches("\\d+") && progress.getLastHour().matches("\\d+")) {
                        if (Integer.parseInt(hourName) < Integer.parseInt(progress.getLastHour())) {
                            continue;
                        }
                    }
                }

                System.out.printf("Processing day/hour %s/%s ...%n", dayName, hourName);

                List<Path> bookFolders = Files.list(hourFolder)
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().matches("\\d+"))
                        .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                        .collect(Collectors.toList());

                for (Path bookFolder : bookFolders) {

                    int bookId = Integer.parseInt(bookFolder.getFileName().toString());

                    if (dayName.equals(progress.getLastDay()) &&
                        hourName.equals(progress.getLastHour()) &&
                        bookId <= progress.getLastIndexedId()) {
                        continue;
                    }

                    Path bodyFile = bookFolder.resolve("body.txt");
                    if (!Files.exists(bodyFile)) {
                        continue;
                    }

                    indexSingleBook(outputDirectory, bodyFile, bookId);
                    booksInBatch++;

                    // Flush batch when reaching BATCH_SIZE
                    if (booksInBatch >= BATCH_SIZE) {
                        flushBatch(outputDirectory);
                        booksInBatch = 0;
                    }

                    progress.setLastIndexedId(Math.max(progress.getLastIndexedId(), bookId));
                    System.out.printf("Indexed book ID %d (%s/%s)%n", bookId, dayName, hourName);
                }

                // Flush remaining batch at end of hour
                if (booksInBatch > 0) {
                    flushBatch(outputDirectory);
                    booksInBatch = 0;
                }

                progress.setLastDay(dayName);
                progress.setLastHour(hourName);
                progress.save(progressPath);

                System.out.printf("Progress saved: %s/%s (last ID: %d)%n",
                        dayName, hourName, progress.getLastIndexedId());
            }
        }

        System.out.printf("Indexing complete. Last indexed: %s/%s%n",
                progress.getLastDay(), progress.getLastHour());
        
        // Final flush in case anything remains
        flushBatch(Paths.get(outputDir));
    }

    private void indexSingleBook(Path outputDirectory, Path bodyFile, int bookId) throws IOException {

        String text = Files.readString(bodyFile).toLowerCase();
        Matcher matcher = WORD_PATTERN.matcher(text);

        Map<String, List<Integer>> termPositions = new HashMap<>();

        int pos = 0;
        while (matcher.find()) {
            String word = matcher.group();

            termPositions
                    .computeIfAbsent(word, k -> new ArrayList<>())
                    .add(pos);

            pos++;
        }

        // Add to batch buffer instead of writing immediately
        for (Map.Entry<String, List<Integer>> entry : termPositions.entrySet()) {
            addToBatch(entry.getKey(), bookId, entry.getValue());
        }
    }

    /**
     * Adds a posting entry to the batch buffer.
     */
    private void addToBatch(String term, int bookId, List<Integer> positions) {
        StringBuilder sb = new StringBuilder();
        sb.append(bookId).append("\t");

        for (int i = 0; i < positions.size(); i++) {
            sb.append(positions.get(i));
            if (i < positions.size() - 1) sb.append(",");
        }
        sb.append("\n");

        batchBuffer
                .computeIfAbsent(term, k -> new ArrayList<>())
                .add(sb.toString());
    }

    /**
     * Flushes all buffered postings to disk in batch.
     * Each term file is written once with all accumulated lines.
     */
    private void flushBatch(Path outputDirectory) throws IOException {
        if (batchBuffer.isEmpty()) {
            return;
        }

        int termsToWrite = batchBuffer.size();
        System.out.printf("Flushing batch: %d terms to disk...%n", termsToWrite);

        for (Map.Entry<String, List<String>> entry : batchBuffer.entrySet()) {
            String term = entry.getKey();
            List<String> lines = entry.getValue();

            String safeName = URLEncoder.encode(term, StandardCharsets.UTF_8);
            Path termFile = outputDirectory.resolve(safeName + ".tsv");

            // Write all lines for this term in one operation
            String content = String.join("", lines);
            Files.writeString(
                    termFile,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }

        batchBuffer.clear();
        System.out.printf("Batch flushed successfully.%n");
    }
}
