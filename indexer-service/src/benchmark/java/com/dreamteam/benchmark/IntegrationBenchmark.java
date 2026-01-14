package com.dreamteam.benchmark;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import com.dreamteam.App;
import com.dreamteam.config.ConfigLoader;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class IntegrationBenchmark {

    private static final int PORT = 7002;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Process serverProcess;
    private Path tempDatalake;
    private Path configFile;

    @Param({"10", "100"})
    private int numBooks;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        tempDatalake = Files.createTempDirectory("integration_datalake_");
        Path dayDir = tempDatalake.resolve("2025-01-01");
        Path hourDir = dayDir.resolve("12");
        Files.createDirectories(hourDir);

        String body = """
            Artificial intelligence connects perception, reasoning,
            and language to enable autonomous decision-making.
            Machine learning algorithms discover patterns in data.
            """;

        for (int i = 1; i <= numBooks; i++) {
            Path bookDir = hourDir.resolve(String.valueOf(i));
            Files.createDirectories(bookDir);
            Files.writeString(bookDir.resolve("body.txt"), body);
            Files.writeString(bookDir.resolve("header.txt"),
                    "Title: Integration Book " + i + "\nAuthor: Benchmark Bot\nRelease Date: 2025\nLanguage: English");
        }

        Path configDir = Files.createTempDirectory("config_bench_");
        configFile = configDir.resolve("config.properties");

        String configContent = String.join("\n",
                "datalake.path=" + tempDatalake,
                "index.output.path=" + configDir.resolve("index.json"),
                "catalog.output.path=" + configDir.resolve("catalog.json"),
                "db.path=" + configDir.resolve("datamart.db"),
                "index.progress.path=" + configDir.resolve("index_progress.json"),
                "catalog.progress.path=" + configDir.resolve("catalog_progress.json"),
                "server.port=" + PORT
        );
        Files.writeString(configFile, configContent);

        System.setProperty("config.file", configFile.toString());

        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.dreamteam.App"
        );
        pb.inheritIO();
        serverProcess = pb.start();

        waitForServerReady();
    }

    private void waitForServerReady() throws InterruptedException {
        int retries = 20;
        while (retries-- > 0) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT + "/status"))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    System.out.println("Server is ready.");
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        throw new RuntimeException("Server did not start in time.");
    }

    @Benchmark
    public void rebuildIndexEndpoint() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/index/rebuild"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Benchmark
    public void updateSingleBookEndpoint() throws IOException, InterruptedException {
        int randomId = 1 + new Random().nextInt(numBooks);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/index/update/" + randomId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Benchmark
    public void getStatusEndpoint() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/index/status"))
                .GET()
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
        }
        deleteIfExists(tempDatalake);
        deleteIfExists(configFile.getParent());
    }

    private void deleteIfExists(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
