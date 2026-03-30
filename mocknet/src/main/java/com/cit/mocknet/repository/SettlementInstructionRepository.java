package com.cit.mocknet.repository;

import com.cit.mocknet.model.SettlementInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {
}
