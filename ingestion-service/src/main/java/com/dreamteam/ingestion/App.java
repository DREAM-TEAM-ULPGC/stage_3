package com.dreamteam.ingestion;

import java.util.HashMap;
import java.util.Map;

import com.dreamteam.common.hazelcast.HazelcastManager;
import com.dreamteam.ingestion.benchmark.DistributedIngestionQueue;
import com.dreamteam.ingestion.broker.IngestionEventPublisher;
import com.dreamteam.ingestion.config.Config;
import com.dreamteam.ingestion.control.BenchmarkController;
import com.dreamteam.ingestion.control.IngestionController;
import com.dreamteam.ingestion.control.ReplicationController;
import com.dreamteam.ingestion.core.IngestionService;
import com.dreamteam.ingestion.replication.DatalakeReplicator;
import com.google.gson.Gson;

import io.javalin.Javalin;

public class App {
	private static final Gson gson = new Gson();
	private static IngestionEventPublisher eventPublisher;
	private static DatalakeReplicator replicator;
	private static DistributedIngestionQueue benchmarkQueue;

	public static void main(String[] args) {
		int port = Config.port();

		// Initialize event publisher for broker integration
		eventPublisher = new IngestionEventPublisher();
		eventPublisher.start();

		// Initialize datalake replicator for cross-node replication
		replicator = new DatalakeReplicator();

		// Initialize ingestion service first (needed by benchmark queue)
		IngestionService service = new IngestionService(
				Config.datalakeDir(),
				Config.ingestionLogFile(),
				eventPublisher,
				replicator
		);

		// Initialize distributed benchmark queue (uses Hazelcast)
		boolean hazelcastEnabled = Config.hazelcastEnabled();
		if (hazelcastEnabled) {
			try {
				benchmarkQueue = new DistributedIngestionQueue(service, Config.benchmarkWorkerThreads());
				System.out.printf("[%s] Hazelcast benchmark queue initialized%n", Config.getNodeId());
			} catch (Exception e) {
				System.err.printf("[%s] Failed to initialize Hazelcast: %s%n", Config.getNodeId(), e.getMessage());
				benchmarkQueue = null;
			}
		}

		// Register shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("Shutting down Ingestion Service...");
			if (benchmarkQueue != null) {
				benchmarkQueue.close();
			}
			if (eventPublisher != null) {
				eventPublisher.close();
			}
			if (replicator != null) {
				replicator.close();
			}
			if (hazelcastEnabled) {
				HazelcastManager.shutdown();
			}
		}));

		Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json")
				.start(port);

		IngestionController controller = new IngestionController(service);
		ReplicationController replicationController = new ReplicationController(Config.datalakeDir());

		app.get("/status", ctx -> {
			Map<String, Object> status = new HashMap<>();
			status.put("service", "ingestion-service");
			status.put("node_id", Config.getNodeId());
			status.put("status", "running");
			status.put("broker_connected", eventPublisher.isConnected());
			status.put("replication_enabled", replicator.isEnabled());
			status.put("replication_factor", replicator.getReplicationFactor());
			status.put("peer_nodes", replicator.getPeerNodes().size());
			status.put("hazelcast_enabled", hazelcastEnabled);
			status.put("benchmark_workers_running", benchmarkQueue != null && benchmarkQueue.isRunning());
			ctx.result(gson.toJson(status));
		});

		// Ingestion endpoints
		app.post("/ingest/{book_id}", controller::ingestBook);
		app.get("/ingest/status/{book_id}", controller::status);
		app.get("/ingest/list", controller::list);

		// Replication endpoints (for receiving from peer nodes)
		app.post("/replicate", replicationController::handleReplication);
		app.get("/datalake/stats", replicationController::getDatalakeStats);
		app.get("/datalake/book/{book_id}/status", replicationController::getBookStatus);

		// Benchmark endpoints (only if Hazelcast is enabled)
		if (benchmarkQueue != null) {
			BenchmarkController benchmarkController = new BenchmarkController(benchmarkQueue);
			app.post("/benchmark/start", benchmarkController::startBenchmark);
			app.post("/benchmark/workers/start", benchmarkController::startWorkers);
			app.post("/benchmark/workers/stop", benchmarkController::stopWorkers);
			app.get("/benchmark/status", benchmarkController::getStatus);
			app.post("/benchmark/reset", benchmarkController::reset);
			app.get("/benchmark/queue/size", benchmarkController::getQueueSize);
			app.get("/benchmark/valid-books", benchmarkController::getValidBooksInfo);
		}

		System.out.printf("Ingestion Service [%s] listening on :%d, datalake=%s, broker=%s, replication=%s, hazelcast=%s%n",
				Config.getNodeId(), port, Config.datalakeDir(), 
				eventPublisher.isConnected() ? "connected" : "disconnected",
				replicator.isEnabled() ? "enabled(factor=" + replicator.getReplicationFactor() + ")" : "disabled",
				hazelcastEnabled ? "enabled" : "disabled");
	}
}
