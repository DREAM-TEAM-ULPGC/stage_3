package com.dreamteam.datamart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;


public class DatamartInitializer {


    public static void initDatamart(String dbPath) throws Exception {
        Path dbFile = Paths.get(dbPath);
        Files.createDirectories(dbFile.getParent());

        String url = "jdbc:sqlite:" + dbPath;

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {

            String sql = """
                CREATE TABLE IF NOT EXISTS books (
                    book_id INTEGER PRIMARY KEY,
                    title TEXT,
                    author TEXT,
                    release_date TEXT,
                    language TEXT
                )
                """;

            statement.execute(sql);
            System.out.println("Datamart initialized at: " + dbFile.toAbsolutePath());

        } catch (SQLException exception) {
            throw new Exception("Failed to initialize datamart: " + exception.getMessage(), exception);
        }
    }
}
