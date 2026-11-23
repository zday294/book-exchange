package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.validation.AmazonProperties;
import com.bookexchange.validation.AmazonUrlValidator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AmazonUrlValidatorTest {

    private final AmazonUrlValidator validator =
            new AmazonUrlValidator(new AmazonProperties(List.of("amazon.com", "amzn.to", "a.co")));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://amazon.com/dp/0547928221",
            "https://www.amazon.com/dp/0547928221?tag=x",
            "https://smile.amazon.com/Hobbit/dp/0547928221/ref=sr_1_1",
            "https://AMAZON.COM/dp/0547928221",
            "https://amzn.to/3abcdef",
            "https://a.co/d/abc123",
            "https://www.amazon.com:443/dp/0547928221"})
    void acceptsAmazonLinks(String url) {
        assertThat(validator.isAllowed(url)).as(url).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://www.amazon.com/dp/0547928221",            // not https
            "ftp://amazon.com/dp/1",
            "https://example.com/dp/0547928221",              // other host
            "https://evilamazon.com/dp/1",                    // lookalike (no dot boundary)
            "https://amazon.com.evil.com/dp/1",               // amazon.com only as a prefix
            "https://amazon.com@evil.com/dp/1",               // userinfo trick: real host is evil.com
            "https://evil.com/?u=https://amazon.com/dp/1",    // amazon only in the query
            "https://amazon.com:8443/dp/1",                   // odd port
            "https://localhost/dp/1",
            "https://10.0.0.1/dp/1",
            "//amazon.com/dp/1",
            "amazon.com/dp/1",
            "not a url",
            "https://amazon.com/has space"})
    void rejectsEverythingElse(String url) {
        assertThat(validator.isAllowed(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIsLeftToNotBlank(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @org.junit.jupiter.api.Test
    void nullIsLeftToNotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
