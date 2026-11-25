package com.bookexchange.book;

import com.bookexchange.domain.BookSubmission;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * What a viewer is allowed to see of a book. Hidden fields are never populated (and, being null,
 * are omitted from the JSON), so nothing secret is ever sent to the browser. The three cases:
 * <ul>
 *   <li>unclaimed: only id, submitter, wrapping paper, {@code claimed=false} and the cover's rounded aspect ratio;</li>
 *   <li>claimed, other viewer: plus claimer, claim time, title and cover (never the book link);</li>
 *   <li>claimed, the claimer: all of the above plus the book link.</li>
 * </ul>
 */
@Schema(description = "A book in the pile, filtered for the viewer. Unclaimed books reveal nothing beyond who "
        + "submitted them and how they are wrapped. Fields that are hidden from this viewer are omitted.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookView(
        @Schema(example = "12") Long id,
        @Schema(example = "Zach") String submitterName,
        @Schema(example = "candy-cane") String wrappingPaper,
        @Schema(description = "Whether someone has claimed this book") boolean claimed,
        @Schema(description = "True only when the X-Participant-Name header matches the claimer "
                + "(case-insensitively)") boolean viewerIsClaimer,
        @Schema(description = "The cover's width divided by its height, rounded to two decimals (0.67 is a typical "
                + "paperback). Returned for unclaimed AND claimed books, so presents can be drawn in the book's "
                + "proportions; omitted until a cover is found. It is the only cover detail revealed before a claim.",
                example = "0.67") java.math.BigDecimal coverAspectRatio,
        @Schema(description = "Claimed books only", example = "Alex") String claimedByName,
        @Schema(description = "Claimed books only", example = "2026-12-01T18:02:11Z") Instant claimedAt,
        @Schema(description = "Claimed books only", example = "The Hobbit") String bookTitle,
        @Schema(description = "Claimed books only; omitted until a cover has been found",
                example = "https://example.com/cover.jpg") String coverImageUrl,
        @Schema(description = "Only returned to the person who claimed the book",
                example = "https://www.amazon.com/dp/0547928221") String bookUrl) {

    /** Builds the view for {@code viewerName} (null/blank = anonymous). */
    public static BookView of(BookSubmission book, String viewerName) {
        if (!book.isClaimed()) {
            return new BookView(book.getId(), book.getSubmitterName(), book.getWrappingPaper(),
                    false, false, book.getCoverAspectRatio(), null, null, null, null, null);
        }
        boolean viewerIsClaimer = viewerName != null && !viewerName.isBlank()
                && book.getClaimedByName().equalsIgnoreCase(viewerName.trim());
        return new BookView(book.getId(), book.getSubmitterName(), book.getWrappingPaper(),
                true, viewerIsClaimer, book.getCoverAspectRatio(), book.getClaimedByName(), book.getClaimedAt(),
                book.getBookTitle(), book.getCoverImageUrl(),
                viewerIsClaimer ? book.getBookUrl() : null);
    }
}
