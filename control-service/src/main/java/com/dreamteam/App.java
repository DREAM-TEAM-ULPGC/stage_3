package com.dreamteam;

import com.dreamteam.control.ControlController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

public class App {
    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.jsonMapper(new JavalinJackson(objectMapper, false));
        }).start(7000);

        new ControlController().registerRoutes(app);
        System.out.println("Control module running on http://localhost:7000");
    }
}