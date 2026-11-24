package com.bookexchange.exchange;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Short, shareable codes that are hard to misread aloud or on paper: no 0/O or 1/I (and no L).
 * 7 characters from 31 symbols is ~27 billion codes, so codes are also not guessable by counting.
 */
@Component
public class RandomExchangeCodeGenerator implements ExchangeCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
