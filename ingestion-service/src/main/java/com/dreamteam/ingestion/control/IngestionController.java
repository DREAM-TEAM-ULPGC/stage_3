package com.dreamteam.ingestion.control;

import java.util.List;
import java.util.Map;

import com.dreamteam.ingestion.core.IngestionService;
import com.dreamteam.ingestion.utils.JsonUtil;
import com.google.gson.Gson;

import io.javalin.http.Context;

public class IngestionController {

	private final IngestionService service;
	private static final Gson GSON = JsonUtil.GSON;

	public IngestionController(IngestionService service) {
		this.service = service;
	}

	public void ingestBook(Context ctx) {
		int bookId;
		try {
			bookId = Integer.parseInt(ctx.pathParam("book_id"));
		} catch (NumberFormatException exception) {
			ctx.status(400);
			ctx.json(Map.of("error", "Invalid book_id format. Must be an integer."));
			return;
		}

		IngestionService.IngestionResult result = service.ingest(bookId);

		Map<String, Object> response = Map.of(
				"book_id", bookId,
				"status", result.status(),
				"path", result.path()
		);

		ctx.status(200);
		ctx.result(GSON.toJson(response));
	}

	public void status(Context ctx) {
		int bookId;
		try {
			bookId = Integer.parseInt(ctx.pathParam("book_id"));
		} catch (NumberFormatException exception) {
			ctx.status(400); 
			ctx.json(Map.of("error", "Invalid book_id format. Must be an integer."));
			return;
		}

		String status = service.status(bookId);

		ctx.status(200);
		ctx.json(Map.of("book_id", bookId, "status", status));
	}

	public void list(Context ctx) {
		List<Integer> books = service.listBooks();

		Map<String, Object> response = Map.of(
			"count", books.size(),
			"books", books
		);

		ctx.status(200);
		ctx.result(GSON.toJson(response));
	}
}