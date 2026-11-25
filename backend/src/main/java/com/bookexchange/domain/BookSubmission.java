package com.bookexchange.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "book_submission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA
public class BookSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id", nullable = false)
    private Exchange exchange;

    @Column(name = "submitter_name", nullable = false, length = 100)
    private String submitterName;

    @Column(name = "book_title", nullable = false, length = 300)
    private String bookTitle;

    @Column(name = "book_url", nullable = false, length = 2048)
    private String bookUrl;

    /** What the cover lookup recognised: an Amazon ASIN / ISBN-10, or a Barnes & Noble ISBN-13. */
    @Column(name = "book_identifier", length = 20)
    @Setter
    private String bookIdentifier;

    @Column(name = "cover_image_url", length = 2048)
    @Setter
    private String coverImageUrl;

    /** Cover width / height rounded to 2 decimals; the only cover fact shown before a claim. */
    @Column(name = "cover_aspect_ratio", precision = 4, scale = 2)
    @Setter
    private java.math.BigDecimal coverAspectRatio;

    @Column(name = "wrapping_paper", nullable = false, length = 50)
    private String wrappingPaper;

    @Column(name = "claimed_by_name", length = 100)
    private String claimedByName;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public BookSubmission(Exchange exchange, String submitterName, String bookTitle,
                          String bookUrl, String wrappingPaper) {
        this.exchange = exchange;
        this.submitterName = submitterName;
        this.bookTitle = bookTitle;
        this.bookUrl = bookUrl;
        this.wrappingPaper = wrappingPaper;
    }

    public boolean isClaimed() {
        return claimedByName != null;
    }

    public void claim(String participantName, Instant at) {
        this.claimedByName = participantName;
        this.claimedAt = at;
    }
}
