package com.bookexchange.repository;

import com.bookexchange.domain.Exchange;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    Optional<Exchange> findByCode(String code);
}
