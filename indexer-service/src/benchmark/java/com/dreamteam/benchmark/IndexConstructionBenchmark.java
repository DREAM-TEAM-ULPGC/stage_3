package com.dreamteam.benchmark;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.dreamteam.core.InvertedIndexer;
import com.dreamteam.progress.ProgressTracker;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class IndexConstructionBenchmark {

    private Path datalakeDir;
    private Path outputFile;
    private Path progressFile;
    private InvertedIndexer indexer;

    private static final String SAMPLE_TEXT = """
        Artificial intelligence and data science enable efficient
        processing of massive datasets and knowledge extraction.
        Neural networks and natural language processing are key
        components of modern computing systems.
        """;

    @Param({"10", "100"})
    private int numBooks;

    @Setup(Level.Iteration)
    public void setup() throws IOException {
        datalakeDir = Files.createTempDirectory("datalake_bench_");
        Path dayDir = datalakeDir.resolve("2025-01-01");
        Files.createDirectories(dayDir);
        Path hourDir = dayDir.resolve("12");
        Files.createDirectories(hourDir);

        for (int i = 1; i <= numBooks; i++) {
            Path bookDir = hourDir.resolve(String.valueOf(i));
            Files.createDirectories(bookDir);
            Files.writeString(bookDir.resolve("body.txt"), SAMPLE_TEXT);
        }

        outputFile = Files.createTempFile("index_output_", ".json");
        progressFile = Files.createTempFile("progress_", ".json");

        Files.writeString(outputFile, "{}");

        Files.writeString(progressFile, "{\"lastDay\":null,\"lastHour\":null,\"lastIndexedId\":0}");

        indexer = new InvertedIndexer(
                datalakeDir.toString(),
                outputFile.toString(),
                progressFile.toString()
        );
    }

    @Benchmark
    public void buildFullIndex() throws IOException {
        indexer.buildIndex();
    }

    @Benchmark
    public void updateSingleBookIndex() throws IOException {
        int randomBookId = 1 + new Random().nextInt(numBooks);
        indexer.updateBookIndex(randomBookId);
    }

    @TearDown(Level.Iteration)
    public void cleanup() throws IOException {
        if (Files.exists(datalakeDir)) deleteRecursively(datalakeDir);
        Files.deleteIfExists(outputFile);
        Files.deleteIfExists(progressFile);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        }
    }
}
