package com.dreamteam.ingestion.benchmark;

import com.dreamteam.ingestion.core.GutenbergSplitter;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@Fork(value = 1)
@Warmup(iterations = 8, time = 2) 
@Measurement(iterations = 15, time = 2) 
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS) 
public class IngestionBenchmark {

    @State(Scope.Benchmark)
    public static class SplitterState {
        public String bookText = loadDummyBookText(); 
        
        private static final String START_MARKER = "*** START OF";
        private static final String END_MARKER = "*** END OF";

        private String loadDummyBookText() {
            String sample = "The Project Gutenberg EBook of A Tale of Two Cities, by Charles Dickens. *** START OF THIS PROJECT GUTENBERG EBOOK A TALE OF TWO CITIES *** It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness...";
            return sample.repeat(20000); 
        }
        
        public int optimizedIndexOfLineContaining(String text, String marker) {
            final int textLength = text.length();
            final int markerLength = marker.length();
            int from = 0;
            
            while (from < textLength) {
                int nl = text.indexOf('\n', from);
                if (nl < 0) {
                    nl = textLength;
                }
                
                int lineStart = from;
                int lineEnd = nl;
                
                for (int i = lineStart; i <= lineEnd - markerLength; i++) {
                    if (text.regionMatches(true, i, marker, 0, markerLength)) {
                        return lineStart; 
                    }
                }
                
                from = nl + 1;
                if (from >= textLength) break;
            }
            return -1;
        }
    }

    @Benchmark
    public GutenbergSplitter.Parts testCurrentSplitHeaderBody(SplitterState state) {
        return GutenbergSplitter.splitHeaderBody(state.bookText);
    }
    
    @Benchmark
    public int testOptimizedIndexSearch(SplitterState state) {
        return state.optimizedIndexOfLineContaining(state.bookText, SplitterState.START_MARKER);
    }
}