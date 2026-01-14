package com.dreamteam.ingestion;

import com.dreamteam.ingestion.broker.IngestionEventPublisher;
import com.dreamteam.ingestion.config.Config;
import com.dreamteam.ingestion.control.IngestionController;
import com.dreamteam.ingestion.core.IngestionService;
import io.javalin.Javalin;
import com.google.gson.Gson;
import java.util.Map;

public class App {
	private static final Gson gson = new Gson();
	private static IngestionEventPublisher eventPublisher;

	public static void main(String[] args) {
		int port = Config.port();

		// Initialize event publisher for broker integration
		eventPublisher = new IngestionEventPublisher();
		eventPublisher.start();

		// Register shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("Shutting down Ingestion Service...");
			if (eventPublisher != null) {
				eventPublisher.close();
			}
		}));

		Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json")
				.start(port);

		IngestionService service = new IngestionService(
				Config.datalakeDir(),
				Config.ingestionLogFile(),
				eventPublisher
		);
		IngestionController controller = new IngestionController(service);

		app.get("/status", ctx -> {
			ctx.result(gson.toJson(Map.of(
					"service", "ingestion-service",
					"status", "running",
					"broker_connected", eventPublisher.isConnected()
			)));
		});

		app.post("/ingest/{book_id}", controller::ingestBook);
		app.get("/ingest/status/{book_id}", controller::status);
		app.get("/ingest/list", controller::list);

		System.out.printf("Ingestion Service listening on :%d, datalake=%s, broker=%s%n",
				port, Config.datalakeDir(), eventPublisher.isConnected() ? "connected" : "disconnected");
	}
}
