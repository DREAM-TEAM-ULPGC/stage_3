package com.dreamteam.benchmark;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.dreamteam.service.IndexerService;
import com.dreamteam.datamart.DatamartInitializer;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class QueryFilterBenchmark {

    private Path datalakeDir;
    private Path indexOutput;
    private Path catalogOutput;
    private Path dbFile;
    private Path indexProgress;
    private Path catalogProgress;

    private IndexerService service;

    private static final String SAMPLE_TEXT = """
        Machine learning enables pattern discovery and predictive
        modeling through algorithms that learn from data.
        Artificial intelligence integrates reasoning, perception,
        and action for adaptive behavior in complex environments.
        """;

    @Param({"10", "100"})
    private int numBooks;

    @Setup(Level.Iteration)
    public void setup() throws Exception {
        datalakeDir = Files.createTempDirectory("querybench_datalake_");
        Path dayDir = datalakeDir.resolve("2025-01-01");
        Path hourDir = dayDir.resolve("12");
        Files.createDirectories(hourDir);

        for (int i = 1; i <= numBooks; i++) {
            Path bookDir = hourDir.resolve(String.valueOf(i));
            Files.createDirectories(bookDir);
            Files.writeString(bookDir.resolve("body.txt"), SAMPLE_TEXT);
            Files.writeString(bookDir.resolve("header.txt"),
                    "Title: Synthetic Book " + i + "\nAuthor: Benchmark Bot\nRelease Date: 2025\nLanguage: English");
        }

        indexOutput = Files.createTempFile("index_", ".json");
        catalogOutput = Files.createTempFile("catalog_", ".json");
        dbFile = Files.createTempFile("datamart_", ".db");
        indexProgress = Files.createTempFile("index_prog_", ".json");
        catalogProgress = Files.createTempFile("catalog_prog_", ".json");

        Files.writeString(indexOutput, "{}");
        Files.writeString(catalogOutput, "{}");
        Files.writeString(indexProgress, "{\"lastDay\":null,\"lastHour\":null,\"lastIndexedId\":0}");
        Files.writeString(catalogProgress, "{\"lastDay\":null,\"lastHour\":null,\"lastIndexedId\":0}");

        DatamartInitializer.initDatamart(dbFile.toString());

        service = new IndexerService(
                datalakeDir.toString(),
                indexOutput.toString(),
                catalogOutput.toString(),
                dbFile.toString(),
                indexProgress.toString(),
                catalogProgress.toString()
        );
    }

    @Benchmark
    public Map<String, Object> rebuildIndexBenchmark() {
        return service.rebuildIndex();
    }

    @Benchmark
    public Map<String, Object> updateSingleBookBenchmark() {
        int randomBookId = 1 + new Random().nextInt(numBooks);
        return service.updateBookIndex(randomBookId);
    }

    @Benchmark
    public Map<String, Object> getStatusBenchmark() {
        return service.getStatus();
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws IOException {
        deleteIfExists(datalakeDir);
        Files.deleteIfExists(indexOutput);
        Files.deleteIfExists(catalogOutput);
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(indexProgress);
        Files.deleteIfExists(catalogProgress);
    }

    private void deleteIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
