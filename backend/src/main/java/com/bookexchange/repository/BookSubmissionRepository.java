package com.bookexchange.repository;

import com.bookexchange.domain.BookSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookSubmissionRepository extends JpaRepository<BookSubmission, Long> {

    List<BookSubmission> findByExchangeIdOrderByIdAsc(Long exchangeId);

    java.util.Optional<BookSubmission> findByIdAndExchangeId(Long id, Long exchangeId);

    @org.springframework.data.jpa.repository.Query(
            "select count(b) > 0 from BookSubmission b "
                    + "where b.exchange.id = :exchangeId and lower(b.submitterName) = lower(:name)")
    boolean existsByExchangeAndSubmitter(
            @org.springframework.data.repository.query.Param("exchangeId") Long exchangeId,
            @org.springframework.data.repository.query.Param("name") String name);

    long countByExchangeId(Long exchangeId);

    long countByExchangeIdAndClaimedByNameIsNotNull(Long exchangeId);

    boolean existsByExchangeIdAndClaimedByNameIgnoreCase(Long exchangeId, String claimedByName);

    /**
     * Atomic "claim if still unclaimed". Returns 1 if this call won, 0 if the book was already
     * claimed (or doesn't exist). Concurrent claims on one book serialize on the row lock.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update BookSubmission b set b.claimedByName = :name, b.claimedAt = :at "
                    + "where b.id = :id and b.exchange.id = :exchangeId and b.claimedByName is null")
    int claimIfUnclaimed(@org.springframework.data.repository.query.Param("id") Long id,
                         @org.springframework.data.repository.query.Param("exchangeId") Long exchangeId,
                         @org.springframework.data.repository.query.Param("name") String name,
                         @org.springframework.data.repository.query.Param("at") java.time.Instant at);

    /** Records the product id and cover found by the lookup. Touches nothing else, so it can't undo a claim. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update BookSubmission b set b.asin = :asin, b.coverImageUrl = :coverUrl where b.id = :id")
    int updateCover(@org.springframework.data.repository.query.Param("id") Long id,
                    @org.springframework.data.repository.query.Param("asin") String asin,
                    @org.springframework.data.repository.query.Param("coverUrl") String coverUrl);
}
