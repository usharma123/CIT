package com.cit.clsnet.settlement.util;

import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.SettlementInstruction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementUtilitiesTest {

    @Test
    void settlementMessageParser_readsNettingSetIds() {
        SettlementMessageParser parser = new SettlementMessageParser();

        List<Long> ids = parser.parseNettingSetIds("{\"nettingSetIds\":[1,2,3]}");

        assertEquals(List.of(1L, 2L, 3L), ids);
    }

    @Test
    void settlementInstructionFactory_createsInstructionForPositiveNet() {
        SettlementInstructionFactory factory = new SettlementInstructionFactory();
        NettingSet nettingSet = nettingSet(9L, new BigDecimal("125.00"));

        Optional<SettlementInstruction> instruction = factory.create(nettingSet);

        assertTrue(instruction.isPresent());
        assertEquals("BANK_B", instruction.orElseThrow().getPayerParty());
        assertEquals("BANK_A", instruction.orElseThrow().getReceiverParty());
    }

    @Test
    void settlementInstructionFactory_skipsZeroNet() {
        SettlementInstructionFactory factory = new SettlementInstructionFactory();

        assertTrue(factory.create(nettingSet(9L, BigDecimal.ZERO)).isEmpty());
    }

    private NettingSet nettingSet(Long id, BigDecimal amount) {
        NettingSet nettingSet = new NettingSet();
        nettingSet.setId(id);
        nettingSet.setCurrency("USD");
        nettingSet.setCounterparty1("BANK_A");
        nettingSet.setCounterparty2("BANK_B");
        nettingSet.setNetAmount(amount);
        return nettingSet;
    }
}
