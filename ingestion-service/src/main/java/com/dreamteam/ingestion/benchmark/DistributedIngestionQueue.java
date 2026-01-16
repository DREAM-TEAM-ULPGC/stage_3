package com.dreamteam.ingestion.benchmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.dreamteam.common.hazelcast.HazelcastManager;
import com.dreamteam.ingestion.config.Config;
import com.dreamteam.ingestion.core.GutenbergBookValidator;
import com.dreamteam.ingestion.core.IngestionService;
import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

/**
 * Distributed ingestion queue using Hazelcast IQueue.
 * Each node in the cluster consumes from the same queue,
 * distributing the workload automatically.
 */
public class DistributedIngestionQueue implements AutoCloseable {

    private static final String QUEUE_NAME = "ingestion-benchmark-queue";
    private static final String STATS_MAP_NAME = "ingestion-benchmark-stats";
    private static final String PROGRESS_MAP_NAME = "ingestion-benchmark-progress";

    private final HazelcastInstance hazelcast;
    private final IQueue<Integer> bookQueue;
    private final IMap<String, Long> statsMap;
    private final IMap<String, Integer> progressMap;
    private final IngestionService ingestionService;
    private final String nodeId;
    private final int workerThreads;

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger localProcessed = new AtomicInteger(0);
    private final AtomicInteger localErrors = new AtomicInteger(0);

    public DistributedIngestionQueue(IngestionService ingestionService) {
        this(ingestionService, Runtime.getRuntime().availableProcessors());
    }

    public DistributedIngestionQueue(IngestionService ingestionService, int workerThreads) {
        this.ingestionService = ingestionService;
        this.nodeId = Config.getNodeId();
        this.workerThreads = workerThreads;

        // Connect to Hazelcast cluster
        this.hazelcast = HazelcastManager.getInstance();
        this.bookQueue = hazelcast.getQueue(QUEUE_NAME);
        this.statsMap = hazelcast.getMap(STATS_MAP_NAME);
        this.progressMap = hazelcast.getMap(PROGRESS_MAP_NAME);

        System.out.printf("[%s] DistributedIngestionQueue initialized with %d worker threads%n",
                nodeId, workerThreads);
    }

    /**
     * Starts a new benchmark by enqueueing n book IDs (1 to n).
     * This should be called on ONE node only - the queue is shared across the cluster.
     *
     * @param n Number of books to ingest (book IDs 1 to n)
     * @return BenchmarkInfo with session details
     */
    public BenchmarkInfo startBenchmark(int n) {
        return startBenchmark(n, false);
    }

    /**
     * Starts a new benchmark by enqueueing book IDs.
     * 
     * @param n Number of books to ingest
     * @param validatedOnly If true, only uses known valid Gutenberg IDs (reduces errors)
     * @return BenchmarkInfo with session details
     */
    public BenchmarkInfo startBenchmark(int n, boolean validatedOnly) {
        // Clear previous benchmark data
        bookQueue.clear();
        statsMap.clear();
        progressMap.clear();

        // Record benchmark start
        long startTime = System.currentTimeMillis();
        String benchmarkId = "bench-" + startTime;
        statsMap.put("benchmark_id", startTime);
        statsMap.put("start_time", startTime);
        statsMap.put("total_books", (long) n);
        statsMap.put("status", 1L); // 1 = running
        statsMap.put("validated_only", validatedOnly ? 1L : 0L);

        // Get book IDs to ingest
        List<Integer> bookIds;
        if (validatedOnly) {
            // Use only known valid Gutenberg IDs (avoids 404 errors)
            bookIds = GutenbergBookValidator.getValidBookIds(n);
            System.out.printf("[%s] Using %d validated Gutenberg IDs (requested %d)%n", 
                    nodeId, bookIds.size(), n);
        } else {
            // Use sequential IDs 1 to n (may include invalid IDs)
            bookIds = java.util.stream.IntStream.rangeClosed(1, n)
                    .boxed()
                    .toList();
        }

        // Enqueue all book IDs
        for (int bookId : bookIds) {
            bookQueue.offer(bookId);
        }

        int actualCount = bookIds.size();
        statsMap.put("total_books", (long) actualCount);

        System.out.printf("[%s] Benchmark started: id=%s, books=%d, queue_size=%d, validated=%s%n",
                nodeId, benchmarkId, actualCount, bookQueue.size(), validatedOnly);

        return new BenchmarkInfo(benchmarkId, actualCount, startTime, "started", nodeId, validatedOnly);
    }

    /**
     * Starts worker threads that consume from the distributed queue.
     * Each node should call this method to participate in the benchmark.
     */
    public void startWorkers() {
        if (running.getAndSet(true)) {
            System.out.printf("[%s] Workers already running%n", nodeId);
            return;
        }

        localProcessed.set(0);
        localErrors.set(0);
        executor = Executors.newFixedThreadPool(workerThreads);

        for (int i = 0; i < workerThreads; i++) {
            final int workerId = i;
            executor.submit(() -> workerLoop(workerId));
        }

        System.out.printf("[%s] Started %d worker threads%n", nodeId, workerThreads);
    }

    /**
     * Worker loop: continuously poll queue and process books.
     */
    private void workerLoop(int workerId) {
        String workerName = nodeId + "-worker-" + workerId;
        System.out.printf("[%s] Worker started%n", workerName);

        while (running.get()) {
            try {
                // Poll with timeout to allow graceful shutdown
                Integer bookId = bookQueue.poll(1, TimeUnit.SECONDS);

                if (bookId == null) {
                    // Check if benchmark is complete (queue empty and status still running)
                    if (bookQueue.isEmpty()) {
                        Long status = statsMap.get("status");
                        if (status != null && status == 1L) {
                            // Mark as complete if we're the first to notice
                            statsMap.putIfAbsent("end_time", System.currentTimeMillis());
                            statsMap.put("status", 2L); // 2 = completed
                        }
                    }
                    continue;
                }

                // Process the book
                long bookStart = System.currentTimeMillis();
                try {
                    IngestionService.IngestionResult result = ingestionService.ingest(bookId);
                    long bookEnd = System.currentTimeMillis();
                    long duration = bookEnd - bookStart;

                    if ("error".equals(result.status())) {
                        localErrors.incrementAndGet();
                        progressMap.compute(nodeId + "_errors", (k, v) -> v == null ? 1 : v + 1);
                        System.out.printf("[%s] Book %d FAILED: %s (took %dms)%n",
                                workerName, bookId, result.path(), duration);
                    } else {
                        localProcessed.incrementAndGet();
                        progressMap.compute(nodeId + "_processed", (k, v) -> v == null ? 1 : v + 1);
                        System.out.printf("[%s] Book %d OK: %s (took %dms)%n",
                                workerName, bookId, result.status(), duration);
                    }

                } catch (Exception e) {
                    localErrors.incrementAndGet();
                    progressMap.compute(nodeId + "_errors", (k, v) -> v == null ? 1 : v + 1);
                    System.err.printf("[%s] Book %d EXCEPTION: %s%n", workerName, bookId, e.getMessage());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.printf("[%s] Worker stopped%n", workerName);
    }

    /**
     * Stops all worker threads gracefully.
     */
    public void stopWorkers() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        System.out.printf("[%s] Workers stopped. Local stats: processed=%d, errors=%d%n",
                nodeId, localProcessed.get(), localErrors.get());
    }

    /**
     * Gets the current benchmark status across all nodes.
     */
    public BenchmarkStatus getStatus() {
        Long benchmarkId = statsMap.get("benchmark_id");
        Long startTime = statsMap.get("start_time");
        Long endTime = statsMap.get("end_time");
        Long totalBooks = statsMap.get("total_books");
        Long status = statsMap.get("status");

        int queueRemaining = bookQueue.size();

        // Aggregate progress from all nodes
        int totalProcessed = 0;
        int totalErrors = 0;
        StringBuilder nodeStats = new StringBuilder();

        for (String key : progressMap.keySet()) {
            Integer value = progressMap.get(key);
            if (value == null) continue;

            if (key.endsWith("_processed")) {
                totalProcessed += value;
                String node = key.replace("_processed", "");
                nodeStats.append(node).append(":").append(value).append(" ");
            } else if (key.endsWith("_errors")) {
                totalErrors += value;
            }
        }

        String statusStr;
        if (status == null || status == 0L) {
            statusStr = "idle";
        } else if (status == 1L) {
            statusStr = "running";
        } else {
            statusStr = "completed";
        }

        long elapsed = 0;
        double throughput = 0;
        if (startTime != null) {
            long end = endTime != null ? endTime : System.currentTimeMillis();
            elapsed = end - startTime;
            if (elapsed > 0 && totalProcessed > 0) {
                throughput = (totalProcessed * 1000.0) / elapsed; // books/sec
            }
        }

        return new BenchmarkStatus(
                benchmarkId != null ? "bench-" + benchmarkId : null,
                statusStr,
                totalBooks != null ? totalBooks.intValue() : 0,
                totalProcessed,
                totalErrors,
                queueRemaining,
                elapsed,
                throughput,
                nodeStats.toString().trim(),
                running.get()
        );
    }

    /**
     * Clears the queue and resets all stats.
     */
    public void reset() {
        stopWorkers();
        bookQueue.clear();
        statsMap.clear();
        progressMap.clear();
        localProcessed.set(0);
        localErrors.set(0);
        System.out.printf("[%s] Benchmark queue reset%n", nodeId);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getQueueSize() {
        return bookQueue.size();
    }

    @Override
    public void close() {
        stopWorkers();
    }

    // ===================== Data classes =====================

    public record BenchmarkInfo(
            String benchmarkId,
            int totalBooks,
            long startTime,
            String status,
            String initiatedBy,
            boolean validatedOnly
    ) {
        // Constructor for backwards compatibility
        public BenchmarkInfo(String benchmarkId, int totalBooks, long startTime, 
                             String status, String initiatedBy) {
            this(benchmarkId, totalBooks, startTime, status, initiatedBy, false);
        }
    }

    public record BenchmarkStatus(
            String benchmarkId,
            String status,
            int totalBooks,
            int processed,
            int errors,
            int queueRemaining,
            long elapsedMs,
            double throughputBooksPerSec,
            String nodeBreakdown,
            boolean localWorkersRunning
    ) {}

    /**
     * Returns the count of known valid Gutenberg book IDs.
     */
    public static int getKnownValidBookCount() {
        return GutenbergBookValidator.getKnownValidCount();
    }
}
