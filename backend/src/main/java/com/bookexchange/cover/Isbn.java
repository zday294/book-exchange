package com.bookexchange.cover;

/** ISBN checks. ISBN-13 (the "ean" in Barnes & Noble links) and, on Amazon, ISBN-10 (a print book's ASIN). Other Amazon products have ids like "B08N5WRWNW". */
public final class Isbn {

    private Isbn() {
    }

    public static boolean isValidIsbn10(String value) {
        if (value == null || value.length() != 10) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = value.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c == 'X' && i == 9) {
                digit = 10;
            } else {
                return false;
            }
            sum += digit * (10 - i);
        }
        return sum % 11 == 0;
    }

    /** 13 digits starting 978 or 979, with a correct check digit. */
    public static boolean isValidIsbn13(String value) {
        if (value == null || value.length() != 13 || !(value.startsWith("978") || value.startsWith("979"))) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }
}
