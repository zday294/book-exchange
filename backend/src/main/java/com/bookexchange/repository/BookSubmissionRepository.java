package com.bookexchange.repository;

import com.bookexchange.domain.BookSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookSubmissionRepository extends JpaRepository<BookSubmission, Long> {

    List<BookSubmission> findByExchangeIdOrderByIdAsc(Long exchangeId);
}
