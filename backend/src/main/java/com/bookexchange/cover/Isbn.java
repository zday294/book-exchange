package com.bookexchange.cover;

/** ISBN-10 check. On Amazon, a print book's ASIN is its ISBN-10; other products have ids like "B08N5WRWNW". */
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
}
