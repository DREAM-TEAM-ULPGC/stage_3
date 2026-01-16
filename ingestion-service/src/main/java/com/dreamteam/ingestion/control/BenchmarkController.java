package com.dreamteam.ingestion.control;

import java.util.Map;

import com.dreamteam.ingestion.benchmark.DistributedIngestionQueue;
import com.google.gson.Gson;

import io.javalin.http.Context;

/**
 * Controller for distributed benchmark operations.
 * Exposes endpoints to start, monitor, and control benchmark ingestion.
 */
public class BenchmarkController {

    private static final Gson GSON = new Gson();

    private final DistributedIngestionQueue queue;

    public BenchmarkController(DistributedIngestionQueue queue) {
        this.queue = queue;
    }

    /**
     * POST /benchmark/start?n=100
     * Starts a new benchmark by enqueueing n books (1 to n) into the distributed queue.
     * Should be called on ONE node - all nodes will process from the same queue.
     */
    public void startBenchmark(Context ctx) {
        String nParam = ctx.queryParam("n");
        if (nParam == null || nParam.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'n'"));
            return;
        }

        int n;
        try {
            n = Integer.parseInt(nParam);
            if (n <= 0 || n > 100000) {
                ctx.status(400).json(Map.of("error", "Parameter 'n' must be between 1 and 100000"));
                return;
            }
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Parameter 'n' must be an integer"));
            return;
        }

        // Start benchmark (enqueue books)
        var info = queue.startBenchmark(n);

        // Auto-start workers on this node
        queue.startWorkers();

        ctx.status(200).json(Map.of(
                "message", "Benchmark started",
                "benchmark_id", info.benchmarkId(),
                "total_books", info.totalBooks(),
                "initiated_by", info.initiatedBy(),
                "start_time", info.startTime(),
                "note", "Workers started on this node. Call POST /benchmark/workers/start on other nodes to join."
        ));
    }

    /**
     * POST /benchmark/workers/start
     * Starts worker threads on this node to consume from the distributed queue.
     * Call this on each node that should participate in the benchmark.
     */
    public void startWorkers(Context ctx) {
        if (queue.isRunning()) {
            ctx.status(200).json(Map.of(
                    "message", "Workers already running on this node",
                    "queue_size", queue.getQueueSize()
            ));
            return;
        }

        queue.startWorkers();
        ctx.status(200).json(Map.of(
                "message", "Workers started on this node",
                "queue_size", queue.getQueueSize()
        ));
    }

    /**
     * POST /benchmark/workers/stop
     * Stops worker threads on this node.
     */
    public void stopWorkers(Context ctx) {
        queue.stopWorkers();
        ctx.status(200).json(Map.of(
                "message", "Workers stopped on this node"
        ));
    }

    /**
     * GET /benchmark/status
     * Returns the current benchmark status across all nodes in the cluster.
     */
    public void getStatus(Context ctx) {
        var status = queue.getStatus();
        ctx.status(200).result(GSON.toJson(status));
    }

    /**
     * POST /benchmark/reset
     * Clears the queue and resets all benchmark statistics.
     */
    public void reset(Context ctx) {
        queue.reset();
        ctx.status(200).json(Map.of(
                "message", "Benchmark queue and stats reset"
        ));
    }

    /**
     * GET /benchmark/queue/size
     * Returns the current queue size (pending books to process).
     */
    public void getQueueSize(Context ctx) {
        ctx.status(200).json(Map.of(
                "queue_size", queue.getQueueSize(),
                "workers_running", queue.isRunning()
        ));
    }
}
