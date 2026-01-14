package com.dreamteam.ingestion.core;

public class GutenbergSplitter {

    public record Parts(String header, String body) {}

    public static Parts splitHeaderBody(String text) {
        String startMarker = "*** START OF";
        String endMarker = "*** END OF";

        int start = indexOfLineContainingOptimized(text, startMarker);
        int end = indexOfLineContainingOptimized(text, endMarker, start >= 0 ? start : 0);

        String header = "";
        String body = text;

        if (start >= 0) {
            header = text.substring(0, start);
            body = text.substring(start);
        }

        if (end >= 0 && end > start) {
            body = text.substring(start >= 0 ? start : 0, end);
        } else if (start >= 0) {
            body = text.substring(start);
        }

        return new Parts(header.strip(), body.strip());
    }

    private static int indexOfLineContainingOptimized(String text, String marker) {
        return indexOfLineContainingOptimized(text, marker, 0);
    }
    
    private static int indexOfLineContainingOptimized(String text, String marker, int startIndex) {
        final int textLength = text.length();
        final int markerLength = marker.length();
        int from = startIndex;
        
        while (from < textLength) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) {
                nl = textLength; 
            }
            
            int lineStart = from;
            int lineEnd = nl;
            
            for (int i = lineStart; i <= lineEnd - markerLength; i++) {
                if (text.regionMatches(true, i, marker, 0, markerLength)) {
                    return lineStart; 
                }
            }
            
            from = nl + 1;
            if (from >= textLength) break;
        }
        return -1;
    }
}