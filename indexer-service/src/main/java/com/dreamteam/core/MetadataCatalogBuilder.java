package com.dreamteam.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.dreamteam.datamart.MetadataParser;
import com.dreamteam.progress.ProgressTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


public class MetadataCatalogBuilder {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final String datalakePath;
    private final String outputPath;
    private final String progressPath;

    public MetadataCatalogBuilder(String datalakePath, String outputPath, String progressPath) {
        this.datalakePath = datalakePath;
        this.outputPath = outputPath;
        this.progressPath = progressPath;
    }

    public void buildCatalog() throws IOException {
        Path datalake = Paths.get(datalakePath);
        Path output = Paths.get(outputPath);

        ProgressTracker progress = ProgressTracker.load(progressPath);
        System.out.println("Last progress: " + progress);

        Map<String, Map<String, String>> catalog = loadCatalog(output);
        boolean processedAny = false;

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
                        return name.matches("\\d+") ? Integer.valueOf(name) : 0;
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
                        .sorted(Comparator.comparingInt(p -> Integer.valueOf(p.getFileName().toString())))
                        .collect(Collectors.toList());

                for (Path bookFolder : bookFolders) {
                    int bookId = Integer.parseInt(bookFolder.getFileName().toString());

                    if (dayName.equals(progress.getLastDay()) &&
                        hourName.equals(progress.getLastHour()) &&
                        bookId <= progress.getLastIndexedId()) {
                        continue;
                    }

                    Path headerFile = bookFolder.resolve("header.txt");
                    if (!Files.exists(headerFile)) {
                        continue;
                    }

                    String headerText = Files.readString(headerFile);
                    Map<String, String> metadata = MetadataParser.parseHeaderMetadata(headerText);

                    if (MetadataParser.hasAnyMetadata(metadata)) {
                        catalog.put(String.valueOf(bookId), metadata);
                        processedAny = true;
                        System.out.printf("Parsed metadata for book ID %d (%s/%s): title=%s, author=%s%n",
                                bookId, dayName, hourName,
                                metadata.get("title"), metadata.get("author"));
                    }

                    progress.setLastIndexedId(Math.max(progress.getLastIndexedId(), bookId));
                }

                saveCatalog(output, catalog);
                progress.setLastDay(dayName);
                progress.setLastHour(hourName);
                progress.save(progressPath);
                System.out.printf("Progress saved: %s/%s (last ID: %d)%n",
                        dayName, hourName, progress.getLastIndexedId());
            }
        }

        if (processedAny) {
            System.out.printf("Finished. Last indexed: %s/%s%n",
                    progress.getLastDay(), progress.getLastHour());
        } else {
            System.out.println("Finished. No headers processed.");
        }
    }

    public void updateBookCatalog(int bookId) throws IOException {
        Path datalake = Paths.get(datalakePath);
        Path output = Paths.get(outputPath);

        Map<String, Map<String, String>> catalog = loadCatalog(output);

        Path bookPath = findBookPath(datalake, bookId);
        if (bookPath == null) {
            throw new IOException("Book ID " + bookId + " not found in datalake");
        }

        Path headerFile = bookPath.resolve("header.txt");
        if (!Files.exists(headerFile)) {
            throw new IOException("header.txt not found for book ID " + bookId);
        }

        String headerText = Files.readString(headerFile);
        Map<String, String> metadata = MetadataParser.parseHeaderMetadata(headerText);

        if (MetadataParser.hasAnyMetadata(metadata)) {
            catalog.put(String.valueOf(bookId), metadata);
            System.out.printf("Updated metadata for book ID %d: title=%s, author=%s%n",
                    bookId, metadata.get("title"), metadata.get("author"));
        } else {
            catalog.remove(String.valueOf(bookId));
            System.out.printf("Removed metadata for book ID %d (no valid metadata found)%n", bookId);
        }

        saveCatalog(output, catalog);
    }

    private Path findBookPath(Path datalake, int bookId) throws IOException {
        String bookIdStr = String.valueOf(bookId);
        
        List<Path> dayFolders = Files.list(datalake)
                .filter(Files::isDirectory)
                .collect(Collectors.toList());

        for (Path dayFolder : dayFolders) {
            List<Path> hourFolders = Files.list(dayFolder)
                    .filter(Files::isDirectory)
                    .collect(Collectors.toList());

            for (Path hourFolder : hourFolders) {
                Path bookFolder = hourFolder.resolve(bookIdStr);
                if (Files.exists(bookFolder) && Files.isDirectory(bookFolder)) {
                    return bookFolder;
                }
            }
        }

        return null;
    }

    private Map<String, Map<String, String>> loadCatalog(Path output) throws IOException {
        if (Files.exists(output)) {
            String json = Files.readString(output);
            Map<String, Map<String, String>> catalog = gson.fromJson(json,
                    new TypeToken<Map<String, Map<String, String>>>(){}.getType());
            return catalog != null ? catalog : new HashMap<>();
        }
        return new HashMap<>();
    }

    private void saveCatalog(Path output, Map<String, Map<String, String>> catalog) throws IOException {
        Files.createDirectories(output.getParent());
        String json = gson.toJson(catalog);
        Files.writeString(output, json);
    }
}
