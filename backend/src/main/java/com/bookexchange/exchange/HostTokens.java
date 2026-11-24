package com.bookexchange.exchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * The host token: a long random secret handed to whoever creates an exchange, once. Whoever holds it
 * is the host (there is no sign-in yet). Only a SHA-256 hash is stored, so a copy of the database
 * can't be used to act as a host. The token is high-entropy random data, so a plain fast hash is
 * appropriate (unlike a human-chosen password).
 */
@Component
public class HostTokens {

    private static final int TOKEN_BYTES = 32; // 256 bits

    private final SecureRandom random = new SecureRandom();

    /** A new random token, URL-safe (43 characters). */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The value that is stored: the hex SHA-256 of the token (64 characters). */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * True when {@code token} is the one that produced {@code storedHash}. The comparison takes the same
     * time whatever the input, so it can't be used to guess a hash bit by bit. A missing token or a
     * missing stored hash (an exchange created before host tokens existed) never matches.
     */
    public boolean matches(String token, String storedHash) {
        if (token == null || token.isBlank() || storedHash == null) {
            return false;
        }
        byte[] candidate = hash(token.strip()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(candidate, storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
