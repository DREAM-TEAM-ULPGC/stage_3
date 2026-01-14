package com.dreamteam.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

public class LogManager {
    private static final String LOG_FILE = "control_log.txt";

    public void log(String message) {
        try (FileWriter fileWriter = new FileWriter(LOG_FILE, true)) {
            fileWriter.write("[" + LocalDateTime.now() + "] " + message + "\n");
        } catch (IOException exception) {
            System.err.println("Cannot write log: " + exception.getMessage());
        }
    }

    public static String readLogs() {
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(LOG_FILE))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) stringBuilder.append(line).append("\n");
        } catch (IOException exception) {
            stringBuilder.append("No logs found.");
        }
        return stringBuilder.toString();
    }
}
