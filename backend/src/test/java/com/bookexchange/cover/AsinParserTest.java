package com.bookexchange.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AsinParserTest {

    static Stream<Arguments> links() {
        return Stream.of(
                Arguments.of("https://www.amazon.com/dp/054792822X", "054792822X"),
                Arguments.of("https://www.amazon.com/dp/054792822X/", "054792822X"),
                Arguments.of("https://www.amazon.com/dp/054792822X?ref=abc&th=1", "054792822X"),
                Arguments.of("https://www.amazon.com/The-Hobbit-J-R-R-Tolkien/dp/054792822X/ref=sr_1_1?keywords=hobbit", "054792822X"),
                Arguments.of("https://www.amazon.com/gp/product/054792822X", "054792822X"),
                Arguments.of("https://www.amazon.com/gp/product/054792822X/ref=ppx_yo", "054792822X"),
                Arguments.of("https://www.amazon.com/gp/aw/d/054792822X", "054792822X"),
                Arguments.of("https://www.amazon.com/gp/offer-listing/054792822X", "054792822X"),
                Arguments.of("https://www.amazon.com/exec/obidos/ASIN/054792822X/somestore-20", "054792822X"),
                Arguments.of("https://www.amazon.com/product/054792822X", "054792822X"),
                Arguments.of("https://smile.amazon.co.uk/dp/B08N5WRWNW", "B08N5WRWNW"),
                Arguments.of("https://www.amazon.com/dp/b08n5wrwnw", "B08N5WRWNW"), // upper-cased
                Arguments.of("https://www.amazon.com/dp/080442957X", "080442957X"));
    }

    @ParameterizedTest
    @MethodSource("links")
    void findsTheAsinInCommonLinkShapes(String url, String expected) {
        assertThat(AsinParser.parse(url)).contains(expected);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://a.co/d/abc123",
            "https://amzn.to/3xYz",
            "https://www.amazon.com/",
            "https://www.amazon.com/s?k=hobbit",
            "https://www.amazon.com/dp/123",            // too short
            "https://www.amazon.com/dp/054792822X1",    // too long
            "https://www.amazon.com/dp/0547-92822",     // not an id
            "not a url at all"})
    void returnsNothingWhenThereIsNoAsin(String url) {
        assertThat(AsinParser.parse(url)).isEmpty();
    }

    @Test
    void isbnCheckDistinguishesBooksFromOtherProducts() {
        assertThat(Isbn.isValidIsbn10("054792822X")).isTrue();
        assertThat(Isbn.isValidIsbn10("0547928221")).isFalse(); // check digit does not match
        assertThat(Isbn.isValidIsbn10("080442957X")).isTrue();
        assertThat(Isbn.isValidIsbn10("B08N5WRWNW")).isFalse(); // a non-book product id
        assertThat(Isbn.isValidIsbn10("12345")).isFalse();
        assertThat(Isbn.isValidIsbn10(null)).isFalse();
    }
}
