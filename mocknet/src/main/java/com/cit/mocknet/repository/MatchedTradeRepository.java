package com.cit.mocknet.repository;

import com.cit.mocknet.model.MatchedTrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchedTradeRepository extends JpaRepository<MatchedTrade, Long> {
}
