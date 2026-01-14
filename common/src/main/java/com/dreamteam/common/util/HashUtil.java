package com.dreamteam.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for computing content hashes.
 * Used for idempotency checks and content deduplication.
 */
public class HashUtil {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * Computes SHA-256 hash of the given content.
     * Returns the hash as a lowercase hexadecimal string.
     */
    public static String sha256(String content) {
        if (content == null) return null;
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes SHA-256 hash of the given bytes.
     * Returns the hash as a lowercase hexadecimal string.
     */
    public static String sha256(byte[] content) {
        if (content == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes a fast hash suitable for quick comparisons.
     * Uses the first 16 characters of SHA-256.
     */
    public static String quickHash(String content) {
        String full = sha256(content);
        return full != null ? full.substring(0, 16) : null;
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
