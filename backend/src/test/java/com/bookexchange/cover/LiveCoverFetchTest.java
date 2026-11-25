package com.bookexchange.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.validation.BookLinkProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Fetches REAL covers through the REAL guarded fetcher, for both identifier kinds (the ISBN-10 an Amazon link
 * gives, the ISBN-13 a Barnes &amp; Noble link gives). Never part of the normal run:
 *
 * <pre>mvn test -Dtest=LiveCoverFetchTest -Dlive.links=true</pre>
 */
@EnabledIfSystemProperty(named = "live.links", matches = "true")
class LiveCoverFetchTest {

    private final GuardedCoverHttp http = new GuardedCoverHttp(
            new CoverProperties(true, "https://covers.openlibrary.org/b/isbn/%s-L.jpg",
                    List.of("covers.openlibrary.org", "archive.org"), 3000, 8000, 2_000_000, 3),
            new BookLinkProperties(List.of("amazon.com", "amzn.to", "a.co", "barnesandnoble.com", "bn.com")));

    private static URI cover(String isbn) {
        return URI.create("https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg?default=false");
    }

    @Test
    void anIsbn10AndTheSameBooksIsbn13BothGiveARealCover() {
        for (String isbn : List.of("054792822X", "9780547928227")) {
            byte[] image = http.fetchImage(cover(isbn)).orElseThrow(() -> new AssertionError("no cover for " + isbn));
            assertThat(ImageShape.aspectRatio(image)).as("shape of " + isbn).isPresent();
        }
    }

    @Test
    void aBookWithoutACoverGivesNothing() {
        assertThat(http.fetchImage(cover("9780547928228"))).isEmpty(); // bad check digit: never a real book
    }
}
