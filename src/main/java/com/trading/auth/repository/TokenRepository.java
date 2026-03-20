package com.trading.auth.repository;

import com.trading.auth.model.ZerodhaToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<ZerodhaToken, Long> {
    Optional<ZerodhaToken> findByAccountIdAndTokenDate(String accountId, LocalDate date);
    void deleteByAccountIdAndTokenDate(String accountId, LocalDate date);
}
