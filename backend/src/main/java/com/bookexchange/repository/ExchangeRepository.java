package com.bookexchange.repository;

import com.bookexchange.domain.Exchange;
import com.bookexchange.domain.ExchangeStatus;
import com.bookexchange.exchange.ExchangeSummary;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    Optional<Exchange> findByCode(String code);

    /**
     * The public list: only exchanges marked listed. Open ones come first, then closed ones; within each,
     * newest first (id breaks ties so pages never repeat or skip an exchange). {@code pattern} is a LIKE
     * pattern over the lower-cased name that uses '!' as its escape character.
     */
    @Query(value = """
            select new com.bookexchange.exchange.ExchangeSummary(
                e.code, e.name, e.description, e.status,
                (select count(b) from BookSubmission b where b.exchange = e),
                (select count(b) from BookSubmission b where b.exchange = e and b.claimedByName is not null),
                e.createdAt)
            from Exchange e
            where e.listed = true and e.status in :statuses and lower(e.name) like :pattern escape '!'
            order by case when e.status = com.bookexchange.domain.ExchangeStatus.OPEN then 0 else 1 end,
                     e.createdAt desc, e.id desc
            """,
            countQuery = """
            select count(e) from Exchange e
            where e.listed = true and e.status in :statuses and lower(e.name) like :pattern escape '!'
            """)
    Page<ExchangeSummary> searchListed(@Param("statuses") Collection<ExchangeStatus> statuses,
                                       @Param("pattern") String pattern, Pageable pageable);
}
