package com.bookexchange.cover;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EanParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://www.barnesandnoble.com/w/the-hobbit-j-r-r-tolkien/1100054405?ean=9780547928227 | 9780547928227",
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?EAN=9780547928227 | 9780547928227",
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?utm_source=x&ean=9780547928227&utm_medium=y | 9780547928227",
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=9780547928227#reviews | 9780547928227",
            "https://www.barnesandnoble.com/w/x/1?ean=1234&ean=9780547928227 | 9780547928227"})
    void readsAValidIsbn13FromTheEanParameter(String url, String expected) {
        assertThat(EanParser.parse(url.trim())).contains(expected.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405",                   // no query
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=",              // empty
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=9780547928228", // bad check digit
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=4006381333931", // an EAN, but not a book
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean=97805479282",   // too short
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?mean=9780547928227", // other parameter
            "https://www.barnesandnoble.com/w/the-hobbit/1100054405?ean",               // no value
            "https://bn.com/short",
            "not a url at all"})
    void returnsNothingWithoutAValidEan(String url) {
        assertThat(EanParser.parse(url)).isEmpty();
    }
}
