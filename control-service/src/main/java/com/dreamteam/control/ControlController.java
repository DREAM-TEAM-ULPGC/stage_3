package com.dreamteam.control;

import java.util.List;
import java.util.Map;

import com.dreamteam.model.ControlRecord;
import com.dreamteam.utils.LogManager;
import com.dreamteam.utils.Orchestrator;
import com.dreamteam.utils.ServiceClient;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class ControlController {
    private final Orchestrator orchestrator = new Orchestrator();

    public void registerRoutes(Javalin app) {
        app.post("/control/start/{book_id}", this::startPipeline);
        app.get("/control/status/{book_id}", this::status);
        app.get("/control/logs", this::getLogs);
        app.get("/control/history", this::history);
        app.post("/control/rebuild", this::rebuildAll);
    }

    private void startPipeline(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("book_id"));
        Map<String, Object> result = orchestrator.runPipeline(bookId);
        ctx.json(result);
    }

    private void status(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("book_id"));
        ControlRecord status = orchestrator.getBookStatus(bookId);
        ctx.json(status);
    }

    private void getLogs(Context ctx) {
        ctx.result(LogManager.readLogs());
    }

    private void history(Context ctx) {
        List<ControlRecord> records = orchestrator.getAllRecords();
        ctx.json(records);
    }

    private void rebuildAll(Context ctx) {
        try {
            ServiceClient client = new ServiceClient();
            client.post("http://localhost:7002/index/rebuild");
            ctx.json(Map.of("status", "index rebuilt"));
        } catch (Exception exception) {
            ctx.json(Map.of("error", exception.getMessage()));
        }
    }
}
