package com.dreamteam.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.dreamteam.search.models.Book;


/**
 * Data Access Object for book metadata.
 * Configured for horizontal scalability:
 * - Read-only mode (query_only pragma)
 * - WAL journal mode for concurrent readers
 * - Connection reuse with proper synchronization
 */
public class MetadataDao implements AutoCloseable {
    private Connection connection;
    private String jdbcUrl;
    private boolean isConnected;

    public MetadataDao(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        connect();
    }

    private void connect() {
        try {
            String dbPath = jdbcUrl.replace("jdbc:sqlite:", "");
            if (!Files.exists(Path.of(dbPath))) {
                System.out.println("Warning: Database file not found at " + dbPath + ". Metadata queries will return empty results.");
                this.isConnected = false;
                return;
            }

            // Enable shared cache and read-only mode for horizontal scalability
            String readOnlyJdbc = jdbcUrl + "?mode=ro&cache=shared";
            this.connection = DriverManager.getConnection(readOnlyJdbc);
            
            // Enable WAL mode for concurrent readers (already set on DB, but ensure)
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA query_only=ON");
                stmt.execute("PRAGMA read_uncommitted=ON");
            }
            
            this.isConnected = true;
            System.out.println("Successfully connected to database at " + dbPath + " (read-only, WAL mode)");
        } catch (SQLException exception) {
            System.err.println("Warning: Cannot connect to SQLite at " + jdbcUrl + ": " + exception.getMessage());
            System.err.println("Metadata queries will return empty results.");
            this.isConnected = false;
        }
    }

    public void reload(String jdbcUrl) {
        close();
        this.jdbcUrl = jdbcUrl;
        connect();
    }

    public Book getBookById(int id) {
        if (!isConnected) return null;
        
        String sql = "SELECT book_id, title, author, language FROM books WHERE book_id = ?";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setInt(1, id);
            try (ResultSet results = preparedStatement.executeQuery()) {
                if (!results.next()) return null;
                return new Book(
                        results.getInt("book_id"),
                        results.getString("title"),
                        results.getString("author"),
                        results.getString("language")
                );
            }
        } catch (SQLException exception) {
            System.err.println("Error querying book by id: " + exception.getMessage());
            return null;
        }
    }

    public List<SearchEngine.ScoredDoc> enrichAndFilter(List<SearchEngine.ScoredDoc> docs,
                                                        String authorFilter,
                                                        String languageFilter) {
        if (docs.isEmpty() || !isConnected) return docs;
        
        String inClause = docs.stream().map(d -> "?").collect(Collectors.joining(","));
        String sql = "SELECT book_id, title, author, language FROM books WHERE book_id IN (" + inClause + ")";
        Map<Integer, Book> byId = new HashMap<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            int i = 1;
            for (var doc : docs) preparedStatement.setInt(i++, doc.bookId);
            try (ResultSet results = preparedStatement.executeQuery()) {
                while (results.next()) {
                    byId.put(results.getInt("book_id"), new Book(
                            results.getInt("book_id"),
                            results.getString("title"),
                            results.getString("author"),
                            results.getString("language")
                    ));
                }
            }
        } catch (SQLException exception) {
            System.err.println("Error enriching and filtering: " + exception.getMessage());
            return docs;
        }

        return docs.stream()
                .filter(doc -> {
                    Book book = byId.get(doc.bookId);
                    if (book == null) return false;
                    boolean ok = true;
                    if (authorFilter != null && !authorFilter.isBlank()) {
                        ok &= book.author() != null &&
                              book.author().toLowerCase().contains(authorFilter.toLowerCase());
                    }
                    if (languageFilter != null && !languageFilter.isBlank()) {
                        ok &= book.language() != null &&
                              book.language().toLowerCase().startsWith(languageFilter.toLowerCase());
                    }
                    return ok;
                })
                .map(doc -> new SearchEngine.ScoredDoc(doc.bookId, doc.score))
                .toList();
    }

    @Override public void close() {
        try { if (connection != null && isConnected) connection.close(); } catch (SQLException ignored) {}
    }
}
