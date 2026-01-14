package com.dreamteam.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.dreamteam.progress.ProgressTracker;

public class InvertedIndexer {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-záéíóúüñ]+\\b");

    private final String datalakePath;
    private final String outputDir;
    private final String progressPath;

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

                    progress.setLastIndexedId(Math.max(progress.getLastIndexedId(), bookId));
                    System.out.printf("Indexed book ID %d (%s/%s)%n", bookId, dayName, hourName);
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

        for (Map.Entry<String, List<Integer>> entry : termPositions.entrySet()) {
            writeTsvPosting(outputDirectory, entry.getKey(), bookId, entry.getValue());
        }
    }

    private void writeTsvPosting(Path outputDir, String term, int bookId, List<Integer> positions) throws IOException {

        String safeName = URLEncoder.encode(term, StandardCharsets.UTF_8);
        Path termFile = outputDir.resolve(safeName + ".tsv");

        StringBuilder sb = new StringBuilder();
        sb.append(bookId).append("\t");

        for (int i = 0; i < positions.size(); i++) {
            sb.append(positions.get(i));
            if (i < positions.size() - 1) sb.append(",");
        }
        sb.append("\n");

        Files.writeString(
                termFile,
                sb.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
}
