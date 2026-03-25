package com.cit.clsnet.repository;

import com.cit.clsnet.model.SettlementInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {
}
