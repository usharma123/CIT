package com.cit.clsnet.repository;

import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    @Query("SELECT t FROM Trade t WHERE " +
           "((t.counterparty1 = :cp1 AND t.counterparty2 = :cp2) OR " +
           " (t.counterparty1 = :cp2 AND t.counterparty2 = :cp1)) " +
           "AND t.currency1 = :ccy1 AND t.currency2 = :ccy2 " +
           "AND t.valueDate = :vd " +
           "AND t.status = :status " +
           "AND t.id <> :excludeId")
    Optional<Trade> findMatchCandidate(
            @Param("cp1") String cp1,
            @Param("cp2") String cp2,
            @Param("ccy1") String ccy1,
            @Param("ccy2") String ccy2,
            @Param("vd") LocalDate vd,
            @Param("status") TradeStatus status,
            @Param("excludeId") Long excludeId);

    List<Trade> findByStatus(TradeStatus status);
}
