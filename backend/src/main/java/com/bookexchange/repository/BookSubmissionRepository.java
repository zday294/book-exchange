package com.bookexchange.repository;

import com.bookexchange.domain.BookSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookSubmissionRepository extends JpaRepository<BookSubmission, Long> {

    List<BookSubmission> findByExchangeIdOrderByIdAsc(Long exchangeId);

    @org.springframework.data.jpa.repository.Query(
            "select count(b) > 0 from BookSubmission b "
                    + "where b.exchange.id = :exchangeId and lower(b.submitterName) = lower(:name)")
    boolean existsByExchangeAndSubmitter(
            @org.springframework.data.repository.query.Param("exchangeId") Long exchangeId,
            @org.springframework.data.repository.query.Param("name") String name);
}
