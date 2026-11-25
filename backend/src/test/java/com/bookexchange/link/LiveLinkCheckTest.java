package com.bookexchange.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookexchange.validation.BookLinkProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the REAL link checker against the REAL stores, to notice if their behaviour changes (for example a store
 * starting to answer 404 to programs, which would make the check reject good links). Never part of the normal run:
 *
 * <pre>mvn test -Dtest=LiveLinkCheckTest -Dlive.links=true</pre>
 *
 * Needs internet. A failure on the "works" links means the check is no longer safe to leave on
 * (set LINK_CHECK_ENABLED=false); a failure on the "dead" links means it no longer catches dead pages.
 */
@EnabledIfSystemProperty(named = "live.links", matches = "true")
class LiveLinkCheckTest {

    private final GuardedLinkChecker checker = new GuardedLinkChecker(
            new LinkCheckProperties(true, 2000, 6000, 5, 0),
            new BookLinkProperties(List.of("amazon.com", "amzn.to", "a.co", "barnesandnoble.com", "bn.com")));

    private LinkStatus check(String url) {
        return checker.check(URI.create(url));
    }

    @Test
    void goodBookLinksAreNotRejected() {
        // OK or UNVERIFIED are both fine (a store may rate-limit); NOT_FOUND would be a false rejection.
        assertThat(check("https://www.amazon.com/dp/054792822X")).isNotEqualTo(LinkStatus.NOT_FOUND);
        assertThat(check("https://www.barnesandnoble.com/w/the-hobbit-j-r-r-tolkien/1100054405?ean=9780547928227"))
                .isNotEqualTo(LinkStatus.NOT_FOUND);
    }

    @Test
    void deadBookLinksAreStillCaught() {
        assertThat(check("https://www.amazon.com/dp/B0ZZZZZZZZ")).isEqualTo(LinkStatus.NOT_FOUND);
        assertThat(check("https://www.barnesandnoble.com/w/does-not-exist/9999999999999")).isEqualTo(LinkStatus.NOT_FOUND);
        assertThat(check("https://a.co/d/doesnotexist")).isEqualTo(LinkStatus.NOT_FOUND);
    }
}
