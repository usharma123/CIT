package com.cit.clsnet.settlement.util;

import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.SettlementStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Component
public class SettlementInstructionFactory {

    public Optional<SettlementInstruction> create(NettingSet nettingSet) {
        BigDecimal netAmount = nettingSet.getNetAmount();
        if (netAmount.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }

        SettlementInstruction instruction = new SettlementInstruction();
        instruction.setNettingSetId(nettingSet.getId());
        instruction.setCurrency(nettingSet.getCurrency());

        if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
            instruction.setPayerParty(nettingSet.getCounterparty2());
            instruction.setReceiverParty(nettingSet.getCounterparty1());
            instruction.setAmount(netAmount);
        } else {
            instruction.setPayerParty(nettingSet.getCounterparty1());
            instruction.setReceiverParty(nettingSet.getCounterparty2());
            instruction.setAmount(netAmount.abs());
        }

        instruction.setStatus(SettlementStatus.GENERATED);
        instruction.setGeneratedAt(Instant.now());
        return Optional.of(instruction);
    }
}
