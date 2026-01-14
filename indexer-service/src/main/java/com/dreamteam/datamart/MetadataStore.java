package com.dreamteam.datamart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;


public class MetadataStore {
    private static final Gson gson = new Gson();

    private final String dbPath;

    public MetadataStore(String dbPath) {
        this.dbPath = dbPath;
    }


    public Map<String, Map<String, String>> loadCatalog(String catalogPath) throws IOException {
        Path path = Paths.get(catalogPath);
        if (!Files.exists(path)) {
            throw new IOException("Catalog not found: " + catalogPath);
        }

        String json = Files.readString(path);
        Map<String, Map<String, String>> catalog = gson.fromJson(json,
                new TypeToken<Map<String, Map<String, String>>>(){}.getType());

        if (catalog == null) {
            throw new IllegalArgumentException("Catalog JSON must be a dict of {book_id: metadata}");
        }

        return catalog;
    }


    public int upsertBooks(Map<String, Map<String, String>> catalog) throws SQLException {
        Path dbFile = Paths.get(dbPath);
        if (!Files.exists(dbFile)) {
            throw new IllegalStateException(
                    "Database not found: " + dbPath + ". Initialize it first with datamart_initializer.");
        }

        String url = "jdbc:sqlite:" + dbPath;
        int affected = 0;

        try (Connection connection = DriverManager.getConnection(url)) {
            String createTable = """
                CREATE TABLE IF NOT EXISTS books (
                    book_id INTEGER PRIMARY KEY,
                    title TEXT,
                    author TEXT,
                    release_date TEXT,
                    language TEXT
                )
                """;
            
            try (var statement = connection.createStatement()) {
                statement.execute(createTable);
            }

            String sql = """
                INSERT INTO books (book_id, title, author, release_date, language)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(book_id) DO UPDATE SET
                    title = excluded.title,
                    author = excluded.author,
                    release_date = excluded.release_date,
                    language = excluded.language
                """;

            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                for (Map.Entry<String, Map<String, String>> entry : catalog.entrySet()) {
                    try {
                        int bookId = Integer.parseInt(entry.getKey());
                        Map<String, String> meta = entry.getValue();

                        preparedStatement.setInt(1, bookId);
                        preparedStatement.setString(2, meta.get("title"));
                        preparedStatement.setString(3, meta.get("author"));
                        preparedStatement.setString(4, meta.get("release_date"));
                        preparedStatement.setString(5, meta.get("language"));
                        preparedStatement.addBatch();
                        affected++;
                    } catch (NumberFormatException exception) {
                        System.err.println("Skipping invalid book_id: " + entry.getKey());
                    }
                }

                preparedStatement.executeBatch();
            }
        }

        return affected;
    }


    public int run(String catalogPath) throws IOException, SQLException {
        Map<String, Map<String, String>> catalog = loadCatalog(catalogPath);
        return upsertBooks(catalog);
    }
}
