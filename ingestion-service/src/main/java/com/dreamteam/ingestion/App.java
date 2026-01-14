package com.dreamteam.ingestion;

import com.dreamteam.ingestion.broker.IngestionEventPublisher;
import com.dreamteam.ingestion.config.Config;
import com.dreamteam.ingestion.control.IngestionController;
import com.dreamteam.ingestion.control.ReplicationController;
import com.dreamteam.ingestion.core.IngestionService;
import com.dreamteam.ingestion.replication.DatalakeReplicator;
import io.javalin.Javalin;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

public class App {
	private static final Gson gson = new Gson();
	private static IngestionEventPublisher eventPublisher;
	private static DatalakeReplicator replicator;

	public static void main(String[] args) {
		int port = Config.port();

		// Initialize event publisher for broker integration
		eventPublisher = new IngestionEventPublisher();
		eventPublisher.start();

		// Initialize datalake replicator for cross-node replication
		replicator = new DatalakeReplicator();

		// Register shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("Shutting down Ingestion Service...");
			if (eventPublisher != null) {
				eventPublisher.close();
			}
			if (replicator != null) {
				replicator.close();
			}
		}));

		Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json")
				.start(port);

		IngestionService service = new IngestionService(
				Config.datalakeDir(),
				Config.ingestionLogFile(),
				eventPublisher,
				replicator
		);
		IngestionController controller = new IngestionController(service);
		ReplicationController replicationController = new ReplicationController(Config.datalakeDir());

		app.get("/status", ctx -> {
			Map<String, Object> status = new HashMap<>();
			status.put("service", "ingestion-service");
			status.put("status", "running");
			status.put("broker_connected", eventPublisher.isConnected());
			status.put("replication_enabled", replicator.isEnabled());
			status.put("replication_factor", replicator.getReplicationFactor());
			status.put("peer_nodes", replicator.getPeerNodes().size());
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

		System.out.printf("Ingestion Service listening on :%d, datalake=%s, broker=%s, replication=%s%n",
				port, Config.datalakeDir(), 
				eventPublisher.isConnected() ? "connected" : "disconnected",
				replicator.isEnabled() ? "enabled(factor=" + replicator.getReplicationFactor() + ")" : "disabled");
	}
}
