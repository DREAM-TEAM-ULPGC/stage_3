package com.dreamteam.benchmark;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import com.dreamteam.core.InvertedIndexer;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class TokenizerBenchmark {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-záéíóúüñ]+\\b");

    private List<String> texts;
    private Matcher matcher;

    private InvertedIndexer indexer;

    @Param({"100", "1000", "5000"})
    private int numDocs;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        indexer = new InvertedIndexer("datalake", "output/index.json", "progress.json");

        String base = """
            La inteligencia artificial permite el análisis masivo de datos, el aprendizaje automático
            y la generación de modelos predictivos con alta eficiencia computacional.
            """;
        texts = new ArrayList<>();
        for (int i = 0; i < numDocs; i++) {
            texts.add(base.repeat(3));
        }
    }

    @Benchmark
    public int tokenizeDocuments() {
        int totalTokens = 0;
        for (String text : texts) {
            matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                totalTokens++;
            }
        }
        return totalTokens;
    }

    @Benchmark
    public Set<String> uniqueTokensPerDocument() {
        Set<String> allUnique = new HashSet<>();
        for (String text : texts) {
            matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                allUnique.add(matcher.group());
            }
        }
        return allUnique;
    }
}
