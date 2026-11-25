package com.bookexchange.cover;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.net.HostMatcher;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.validation.BookUrlValidator;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Works out which book a link points to and finds its cover:
 * <ol>
 *   <li>read the book's identifier from the link: the ASIN on Amazon (following a short a.co / amzn.to
 *       link first if needed), the {@code ean} (an ISBN-13) on Barnes &amp; Noble;</li>
 *   <li>if it is a valid ISBN-10 or ISBN-13 (print books), ask the free cover source for the cover;</li>
 *   <li>the downloaded image's width / height is stored too (rounded), so presents can be drawn in the book's shape;</li>
 *   <li>otherwise, or if there is no such cover, leave it empty. The page shows a placeholder.</li>
 * </ol>
 * Neither store's own pages are ever downloaded for their images. A Barnes &amp; Noble link without a usable
 * {@code ean} (such as a short {@code bn.com} form) is not followed to find one: it simply gets no cover.
 */
@Component
@ConditionalOnProperty(prefix = "app.cover", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IsbnCoverLookup implements CoverLookup {

    private static final Logger log = LoggerFactory.getLogger(IsbnCoverLookup.class);

    private final BookSubmissionRepository books;
    private final CoverHttp http;
    private final BookUrlValidator bookUrls;
    private final CoverProperties props;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    public IsbnCoverLookup(BookSubmissionRepository books, CoverHttp http, BookUrlValidator bookUrls,
                           CoverProperties props, TransactionTemplate transaction, ApplicationEventPublisher events) {
        this.transaction = transaction;
        this.events = events;
        this.books = books;
        this.http = http;
        this.bookUrls = bookUrls;
        this.props = props;
    }

    /** Only Amazon's short links (a.co, amzn.to) hide an ASIN behind a redirect; other stores' links are not followed. */
    private static boolean isAmazonShortLink(String link) {
        String host = URI.create(link).getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("a.co") || host.equals("amzn.to") || host.endsWith(".a.co") || host.endsWith(".amzn.to");
    }

    private static final java.util.List<String> BARNES_AND_NOBLE_HOSTS = java.util.List.of("barnesandnoble.com", "bn.com");

    private static boolean isBarnesAndNoble(String link) {
        return HostMatcher.matches(URI.create(link).getHost(), BARNES_AND_NOBLE_HOSTS);
    }

    /** The book identifier a link carries (following an Amazon short link if needed), or empty. */
    private Optional<String> identify(String link) {
        if (isBarnesAndNoble(link)) {
            return EanParser.parse(link);
        }
        Optional<String> asin = AsinParser.parse(link);
        if (asin.isEmpty() && isAmazonShortLink(link)) {
            asin = http.resolveShortLink(URI.create(link)).flatMap(AsinParser::parse);
        }
        return asin;
    }

    @Override
    public void resolveCover(Long bookId) {
        Optional<BookSubmission> found = books.findById(bookId);
        if (found.isEmpty()) {
            return;
        }
        String link = found.get().getBookUrl();
        if (!bookUrls.isAllowed(link)) {
            return; // never fetch anything that is not on the allow-list
        }
        Optional<String> identifier = identify(link);
        if (identifier.isEmpty()) {
            log.info("Book {}: no book identifier found in its link, so no cover is looked up", bookId);
            return;
        }
        String coverUrl = null;
        BigDecimal ratio = null;
        if (Isbn.isValidIsbn10(identifier.get()) || Isbn.isValidIsbn13(identifier.get())) {
            String url = props.imageUrlTemplate().formatted(identifier.get());
            // "default=false" makes the source answer 404 instead of a blank placeholder picture.
            Optional<byte[]> image = http.fetchImage(URI.create(url + "?default=false"));
            if (image.isPresent()) {
                coverUrl = url;
                ratio = ImageShape.aspectRatio(image.get()).orElse(null);
            }
        }
        String foundIdentifier = identifier.get();
        BigDecimal foundRatio = ratio;
        String foundCover = coverUrl;
        transaction.executeWithoutResult(status -> {
            books.updateCover(bookId, foundIdentifier, foundCover, foundRatio);
            if (foundRatio != null) {
                books.findExchangeCodeById(bookId).ifPresent(code ->
                        events.publishEvent(new BookShapeKnownEvent(bookId, code, foundRatio)));
            }
        });
        log.info("Book {}: identifier {} cover {} ratio {}", bookId, foundIdentifier, foundCover == null ? "not found" : "found", foundRatio);
    }
}
