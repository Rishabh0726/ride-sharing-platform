package com.rideshare.pricing_service.repository;

import com.rideshare.pricing_service.entity.FareQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FareQuoteRepository extends JpaRepository<FareQuote, Long> {

    Optional<FareQuote> findByQuoteToken(String quoteToken);

    boolean existsByQuoteToken(String quoteToken);
}
