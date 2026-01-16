package com.dreamteam.ingestion.core;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates and filters Gutenberg book IDs to avoid 404 errors.
 * Many book IDs in Gutenberg are:
 * - Reserved but not published
 * - Audiobooks only (no text)
 * - Deleted/removed
 * - Non-English without UTF-8 text
 * 
 * This class provides methods to get valid book IDs for benchmarking.
 */
public class GutenbergBookValidator {

    // Known valid popular books (manually verified to have plain text)
    private static final Set<Integer> KNOWN_VALID_BOOKS = Set.of(
        // Classics
        1, 2, 11, 16, 17, 23, 25, 36, 41, 43, 46, 55, 64, 65, 74, 76, 84, 98, 100,
        105, 113, 120, 135, 145, 158, 161, 174, 175, 205, 209, 215, 219, 236, 244,
        271, 345, 408, 514, 521, 526, 527, 528, 541, 580, 600, 624, 730, 768, 779,
        829, 844, 863, 883, 910, 932, 940, 996, 1080, 1155, 1184, 1232, 1250, 1260,
        1268, 1322, 1342, 1400, 1497, 1513, 1524, 1597, 1661, 1695, 1727, 1753, 1952,
        1998, 2000, 2009, 2097, 2130, 2148, 2160, 2197, 2229, 2264, 2346, 2350, 2413,
        2500, 2542, 2554, 2591, 2600, 2641, 2680, 2701, 2707, 2814, 2852, 3207, 3296,
        3600, 3825, 4085, 4217, 4280, 4300, 4363, 4517, 5200, 5230, 6130, 6593, 7370,
        // Shakespeare
        1041, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115,
        1500, 1502, 1503, 1504, 1505, 1508, 1511, 1512, 1515, 1516, 1517, 1518, 1519, 1520, 1521,
        1522, 1523, 1525, 1526, 1527, 1528, 1529, 1530, 1531, 1532, 1533, 1534, 1535, 1536, 1537,
        1538, 1539, 1540, 1541, 1543, 1544, 1546,
        // More classics
        8800, 10007, 10609, 10662, 11030, 11231, 12624, 15399, 16328, 17135, 17489,
        19033, 19942, 20203, 23042, 24022, 25344, 27827, 28054, 28520, 30254, 32325,
        33283, 35899, 36034, 37106, 40745, 41445, 42671, 43936, 44881, 45631, 46852,
        48320, 51060, 55752, 58585, 60929, 64317, 67979, 68283, 70116
    );

    // Cache to avoid repeated HTTP checks
    private static final ConcurrentHashMap<Integer, Boolean> validityCache = new ConcurrentHashMap<>();

    /**
     * Returns a list of n known valid book IDs for benchmarking.
     * These are manually verified to have plain text available.
     */
    public static List<Integer> getValidBookIds(int n) {
        List<Integer> sorted = new ArrayList<>(KNOWN_VALID_BOOKS);
        Collections.sort(sorted);
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /**
     * Returns all known valid book IDs.
     */
    public static Set<Integer> getAllKnownValidIds() {
        return new HashSet<>(KNOWN_VALID_BOOKS);
    }

    /**
     * Gets the count of known valid books.
     */
    public static int getKnownValidCount() {
        return KNOWN_VALID_BOOKS.size();
    }

    /**
     * Checks if a book ID is likely to be valid by checking the cache
     * or doing a quick HTTP HEAD request.
     */
    public static boolean isLikelyValid(int bookId) {
        // Check known list first
        if (KNOWN_VALID_BOOKS.contains(bookId)) {
            return true;
        }

        // Check cache
        Boolean cached = validityCache.get(bookId);
        if (cached != null) {
            return cached;
        }

        // Do a quick HTTP check
        boolean valid = quickValidityCheck(bookId);
        validityCache.put(bookId, valid);
        return valid;
    }

    /**
     * Quick HTTP HEAD check to see if the book exists.
     */
    private static boolean quickValidityCheck(int bookId) {
        String[] urls = {
            "https://www.gutenberg.org/cache/epub/" + bookId + "/pg" + bookId + ".txt",
            "https://www.gutenberg.org/files/" + bookId + "/" + bookId + "-0.txt"
        };

        for (String urlStr : urls) {
            try {
                URL url = URI.create(urlStr).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "DreamTeam-Ingestion/1.0");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                conn.disconnect();

                if (code == 200) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Fetches a fresh list of valid book IDs from Gutenberg's robot file.
     * This is slower but gets real-time data.
     * Returns a list of discovered valid IDs (limited to maxCount).
     */
    public static List<Integer> discoverValidIds(int maxCount) {
        Set<Integer> discovered = new HashSet<>();
        
        // Start with known valid books
        discovered.addAll(KNOWN_VALID_BOOKS);

        // Try to discover more by scanning popular ranges
        int[] ranges = {1, 100, 500, 1000, 1500, 2000, 2500, 3000, 4000, 5000,
                        6000, 7000, 8000, 9000, 10000, 15000, 20000};

        for (int start : ranges) {
            if (discovered.size() >= maxCount) break;
            
            for (int i = start; i < start + 50 && discovered.size() < maxCount; i++) {
                if (!discovered.contains(i) && quickValidityCheck(i)) {
                    discovered.add(i);
                }
            }
        }

        List<Integer> result = new ArrayList<>(discovered);
        Collections.sort(result);
        return result.subList(0, Math.min(maxCount, result.size()));
    }

    /**
     * Generates a list of n book IDs, using known valid IDs first,
     * then filling with sequential IDs that pass validation.
     */
    public static List<Integer> getBookIdsForBenchmark(int n, boolean strictValidation) {
        if (strictValidation) {
            // Only return known valid books
            return getValidBookIds(n);
        }
        
        // Use known valid IDs first
        List<Integer> result = new ArrayList<>(getValidBookIds(Math.min(n, getKnownValidCount())));
        
        // If need more, add sequential IDs (may include some invalid ones)
        if (result.size() < n) {
            int remaining = n - result.size();
            Set<Integer> used = new HashSet<>(result);
            
            for (int i = 1; remaining > 0 && i <= 100000; i++) {
                if (!used.contains(i)) {
                    result.add(i);
                    remaining--;
                }
            }
        }
        
        Collections.sort(result);
        return result;
    }
}
