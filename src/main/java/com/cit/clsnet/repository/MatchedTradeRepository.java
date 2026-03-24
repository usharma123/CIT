package com.cit.clsnet.repository;

import com.cit.clsnet.model.MatchedTrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchedTradeRepository extends JpaRepository<MatchedTrade, Long> {
}
