package com.dreamteam.datamart;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MetadataParser {

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^\\s*Title\\s*:\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    private static final Pattern AUTHOR_PATTERN = Pattern.compile(
            "^\\s*Author\\s*:\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    private static final Pattern RELEASE_DATE_PATTERN = Pattern.compile(
            "^\\s*Release\\s+date\\s*:\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "^\\s*Language\\s*:\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    private static final Pattern EBOOK_BRACKET_PATTERN = Pattern.compile(
            "\\s*\\[eBook\\s*#\\d+\\]\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    public static Map<String, String> parseHeaderMetadata(String headerText) {
        Map<String, String> metadata = new HashMap<>();

        metadata.put("title", extractField(headerText, TITLE_PATTERN, false));
        metadata.put("author", extractField(headerText, AUTHOR_PATTERN, false));
        metadata.put("release_date", extractField(headerText, RELEASE_DATE_PATTERN, true));
        metadata.put("language", extractField(headerText, LANGUAGE_PATTERN, false));

        return metadata;
    }

    private static String extractField(String text, Pattern pattern, boolean cleanEbookBracket) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1).strip();
            if (cleanEbookBracket) {
                value = EBOOK_BRACKET_PATTERN.matcher(value).replaceAll("").strip();
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    public static boolean hasAnyMetadata(Map<String, String> metadata) {
        return metadata.values().stream().anyMatch(v -> v != null && !v.isEmpty());
    }
}