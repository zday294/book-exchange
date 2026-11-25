package com.bookexchange.cover;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.validation.AmazonUrlValidator;
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
 *   <li>read the ASIN from the link (following a short a.co / amzn.to link first if needed);</li>
 *   <li>if the ASIN is an ISBN-10 (print books), ask the free cover source for the cover;</li>
 *   <li>the downloaded image's width / height is stored too (rounded), so presents can be drawn in the book's shape;</li>
 *   <li>otherwise, or if there is no such cover, leave it empty. The page shows a placeholder.</li>
 * </ol>
 * Amazon's own pages are never downloaded for their images.
 */
@Component
@ConditionalOnProperty(prefix = "app.cover", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IsbnCoverLookup implements CoverLookup {

    private static final Logger log = LoggerFactory.getLogger(IsbnCoverLookup.class);

    private final BookSubmissionRepository books;
    private final CoverHttp http;
    private final AmazonUrlValidator amazonUrls;
    private final CoverProperties props;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    public IsbnCoverLookup(BookSubmissionRepository books, CoverHttp http, AmazonUrlValidator amazonUrls,
                           CoverProperties props, TransactionTemplate transaction, ApplicationEventPublisher events) {
        this.transaction = transaction;
        this.events = events;
        this.books = books;
        this.http = http;
        this.amazonUrls = amazonUrls;
        this.props = props;
    }

    @Override
    public void resolveCover(Long bookId) {
        Optional<BookSubmission> found = books.findById(bookId);
        if (found.isEmpty()) {
            return;
        }
        String link = found.get().getAmazonUrl();
        if (!amazonUrls.isAllowed(link)) {
            return; // never fetch anything that is not on the allow-list
        }
        Optional<String> asin = AsinParser.parse(link);
        if (asin.isEmpty()) {
            asin = http.resolveShortLink(URI.create(link)).flatMap(AsinParser::parse);
        }
        if (asin.isEmpty()) {
            log.info("Book {}: no ASIN found in its link", bookId);
            return;
        }
        String coverUrl = null;
        BigDecimal ratio = null;
        if (Isbn.isValidIsbn10(asin.get())) {
            String url = props.imageUrlTemplate().formatted(asin.get());
            // "default=false" makes the source answer 404 instead of a blank placeholder picture.
            Optional<byte[]> image = http.fetchImage(URI.create(url + "?default=false"));
            if (image.isPresent()) {
                coverUrl = url;
                ratio = ImageShape.aspectRatio(image.get()).orElse(null);
            }
        }
        String foundAsin = asin.get();
        BigDecimal foundRatio = ratio;
        String foundCover = coverUrl;
        transaction.executeWithoutResult(status -> {
            books.updateCover(bookId, foundAsin, foundCover, foundRatio);
            if (foundRatio != null) {
                books.findExchangeCodeById(bookId).ifPresent(code ->
                        events.publishEvent(new BookShapeKnownEvent(bookId, code, foundRatio)));
            }
        });
        log.info("Book {}: ASIN {} cover {} ratio {}", bookId, foundAsin, foundCover == null ? "not found" : "found", foundRatio);
    }
}
