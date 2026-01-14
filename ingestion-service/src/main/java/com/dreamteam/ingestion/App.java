package com.dreamteam.ingestion;

import com.dreamteam.ingestion.config.Config;
import com.dreamteam.ingestion.control.IngestionController;
import com.dreamteam.ingestion.core.IngestionService;
import io.javalin.Javalin;
import com.google.gson.Gson;
import java.util.Map;

public class App {
	private static final Gson gson = new Gson();

	public static void main(String[] args) {
		int port = Config.port();
		Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json")
				.start(port);

		IngestionService service = new IngestionService(
				Config.datalakeDir(),
				Config.ingestionLogFile()
		);
		IngestionController controller = new IngestionController(service);

		app.get("/status", ctx -> {
			ctx.result(gson.toJson(Map.of("service", "ingestion-service", "status", "running")));
		});

		app.post("/ingest/{book_id}", controller::ingestBook);
		app.get("/ingest/status/{book_id}", controller::status);
		app.get("/ingest/list", controller::list);

		System.out.printf("Ingestion Service listening on :%d, datalake=%s%n",
				port, Config.datalakeDir());
	}
}
