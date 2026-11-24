package com.bookexchange.cover;

import com.bookexchange.domain.BookSubmission;
import com.bookexchange.repository.BookSubmissionRepository;
import com.bookexchange.validation.AmazonUrlValidator;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Works out which book a link points to and finds its cover:
 * <ol>
 *   <li>read the ASIN from the link (following a short a.co / amzn.to link first if needed);</li>
 *   <li>if the ASIN is an ISBN-10 (print books), ask the free cover source for the cover;</li>
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

    public IsbnCoverLookup(BookSubmissionRepository books, CoverHttp http, AmazonUrlValidator amazonUrls,
                           CoverProperties props) {
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
        if (Isbn.isValidIsbn10(asin.get())) {
            String url = props.imageUrlTemplate().formatted(asin.get());
            // "default=false" makes the source answer 404 instead of a blank placeholder picture.
            if (http.fetchImage(URI.create(url + "?default=false")).isPresent()) {
                coverUrl = url;
            }
        }
        books.updateCover(bookId, asin.get(), coverUrl);
        log.info("Book {}: ASIN {} cover {}", bookId, asin.get(), coverUrl == null ? "not found" : "found");
    }
}
