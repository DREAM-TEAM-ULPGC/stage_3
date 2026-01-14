package com.dreamteam.benchmark;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.dreamteam.datamart.MetadataParser;
import com.dreamteam.datamart.MetadataStore;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class MetadataParsingBenchmark {

    private static final String HEADER_SAMPLE = """
        Title: Artificial Intelligence: Foundations
        Author: Alan M. Turing
        Release Date: 1950 [eBook #12345]
        Language: English
        """;

    private MetadataStore store;
    private Map<String, Map<String, String>> catalog;
    private Path tempDbPath;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDbPath = Files.createTempFile("metadata_bench", ".db");
        store = new MetadataStore(tempDbPath.toString());

        catalog = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            Map<String, String> meta = MetadataParser.parseHeaderMetadata(HEADER_SAMPLE);
            catalog.put(String.valueOf(1000 + i), meta);
        }
    }

    @Benchmark
    public Map<String, String> parseSingleHeader() {
        return MetadataParser.parseHeaderMetadata(HEADER_SAMPLE);
    }

    @Benchmark
    public int insertParsedCatalog() throws SQLException {
        return store.upsertBooks(catalog);
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        if (Files.exists(tempDbPath)) {
            Files.delete(tempDbPath);
        }
    }
}
