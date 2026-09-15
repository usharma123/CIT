# Mocknet OTel Live Waterfall — Trade Pair TRD-OTEL-1775072478A/B

**traceID:** `88188a467602bc09c60e697997a8a237`
**spans:** 195  |  **end-to-end:** 160.0ms
**Jaeger:** http://localhost:16686/trace/88188a467602bc09c60e697997a8a237

---

## Waterfall Trace

```
STAGE         COMPONENT                                    t+start    duration
────────────────────────────────────────────────────────────────────────────────

── HTTP ──────────────────────────────────────────────────────────────────────
              POST /api/trades                             t+  0.0ms   4.863ms
                TradeSubmissionController.submitTrade      t+  0.6ms   3.566ms
                  QueueBroker.publish → INGESTION          t+  1.9ms   2.081ms
                    QueueMessageRepository.save            t+  2.4ms   1.310ms
                      INSERT queue_messages                t+  3.2ms   0.153ms
                  Transaction.commit                       t+  4.0ms   0.148ms

── [QUEUE BOUNDARY: traceparent injected into QueueMessage.traceContext] ─────

── INGESTION ──────────────────────────────────────────────────────────────── (~37ms queue wait)
              QueueMessage.process [CONSUMER]              t+ 37.0ms   6.144ms
                TradeIngestionService.processTradeXml      t+ 37.2ms   2.094ms
                  TradeXmlParser.parse                     t+ 37.3ms   0.295ms
                  TradeValidator.validate                  t+ 37.6ms   0.023ms
                    CurrencyValidationService.isSupported  t+ 37.6ms   0.005ms  (USD ✓)
                    CurrencyValidationService.isSupported  t+ 37.6ms   0.002ms  (EUR ✓)
                  TradeEntityMapper.mapToTrade             t+ 37.7ms   0.005ms
                  TradeRepository.save (INSERT trades)     t+ 37.7ms   0.908ms
                    INSERT ./data/coredb.trades            t+ 38.0ms   0.563ms
                  Transaction.commit                       t+ 38.7ms   0.083ms
                  QueueBroker.publish → MATCHING           t+ 38.8ms   0.458ms
                    INSERT queue_messages {tradeId:3571}   t+ 39.1ms   0.060ms
                  Transaction.commit                       t+ 39.2ms   0.084ms
                QueueBroker.complete (INGESTION DONE)      t+ 39.4ms   3.431ms
                  UPDATE queue_messages → DONE             t+ 42.6ms   0.139ms

── [QUEUE BOUNDARY: traceparent propagated] ──────────────────────────────────

── MATCHING ──────────────────────────────────────────────────────────────────
              QueueMessage.process [CONSUMER]              t+ 41.7ms  22.348ms
                TradeMatchingEngine.processMatchingMessage t+ 42.3ms  20.966ms
                  MatchingMessageParser.parseTradeId       t+ 42.4ms   0.489ms
                  TradeRepository.findById (trade 3571)    t+ 44.1ms   0.435ms
                    SELECT trades                          t+ 44.4ms   0.024ms
                  TradeRepository.findMatchCandidate       t+ 45.3ms   2.083ms
                    → found TRD-OTEL-1775072478B           t+ 46.6ms   0.719ms
                  MatchedTradeFactory.create               t+ 47.4ms   0.041ms
                  MatchedTradeRepository.save → id=658     t+ 47.6ms   1.928ms
                    INSERT matched_trades                  t+ 48.9ms   0.511ms
                  TradeRepository.save (A → MATCHED)       t+ 49.6ms   2.421ms
                  TradeRepository.save (B → MATCHED)       t+ 52.1ms   0.090ms
                  Transaction.commit                       t+ 52.2ms  10.273ms
                    UPDATE trades (×2)                     t+ 60.9ms   0.381ms
                  QueueBroker.publish → NETTING            t+ 62.5ms   0.627ms
                    INSERT queue_messages {matchedTradeId:658}
                QueueBroker.complete (MATCHING DONE)       t+ 63.3ms   0.683ms

── [QUEUE BOUNDARY: traceparent propagated] ──────────────────────────────────

── NETTING + 2PC + SETTLEMENT ────────────────────────────────────────────── (~77ms queue wait)
              QueueMessage.process [CONSUMER]              t+141.3ms  18.728ms
                NettingCalculator.processNettingMessage    t+141.9ms  17.513ms
                  NettingMessageParser.parseMatchedTradeId t+142.0ms   0.074ms
                  TwoPhaseCommitCoordinator.executeTransaction t+142.1ms 17.316ms

                  ── PHASE 1 — PREPARE ──────────────────────────────────────
                    TransactionLogRepository.save (INIT)   t+142.9ms   2.185ms
                      INSERT transaction_log               t+143.3ms   1.712ms
                    Transaction.commit                     t+145.1ms   0.107ms
                    TransactionLogRepository.findById      t+146.5ms   0.817ms
                    TransactionLogRepository.save (UPDATE) t+147.5ms   0.058ms
                    Transaction.commit                     t+147.6ms   0.425ms
                      UPDATE transaction_log               t+147.8ms   0.091ms
                    MatchedTradeRepository.findById (658)  t+148.5ms   0.366ms
                    TradeRepository.findById (A)           t+148.9ms   0.229ms
                    TradeRepository.findById (B)           t+149.1ms   0.131ms
                    ParticipantVoteFactory.commitVote (NettingCalc) t+149.3ms 0.034ms
                    ParticipantVoteRepository.save         t+149.5ms   0.662ms
                      INSERT participant_votes             t+149.8ms   0.330ms
                    Transaction.commit                     t+150.3ms   0.120ms
                    MatchedTradeRepository.findById (658)  t+150.6ms   0.118ms
                    ParticipantVoteFactory.commitVote (SettleInstr) t+150.7ms 0.004ms
                    ParticipantVoteRepository.save         t+150.7ms   0.165ms
                      INSERT participant_votes             t+150.8ms   0.036ms
                    Transaction.commit                     t+151.0ms   0.074ms
                    ─ all votes = COMMIT ─

                  ── PHASE 2 — COMMIT ───────────────────────────────────────
                    TransactionLogRepository.findById ×2   t+151.1ms   0.374ms
                    TransactionLogRepository.save ×2       t+151.3ms   0.100ms
                    Transaction.commit ×2                  t+151.3ms → 152.0ms
                    MatchedTradeRepository.findById (658)  t+152.6ms   0.130ms
                    TradeRepository.findById (A)           t+152.9ms   0.133ms
                    TradeRepository.findById (B)           t+153.2ms   0.144ms
                    NettingSetFactory.create               t+153.5ms   0.606ms
                    NettingSetRepository.save → id=1153    t+154.6ms   0.867ms
                      INSERT netting_sets (USD leg)        t+155.0ms   0.371ms
                    NettingSetRepository.save → id=1154    t+155.5ms   0.180ms
                      INSERT netting_sets (EUR leg)        t+155.6ms   0.030ms
                    MatchedTradeRepository.save (NETTED)   t+155.7ms   0.053ms
                    TradeRepository.save (A → NETTED)      t+155.7ms   0.056ms
                    TradeRepository.save (B → NETTED)      t+155.8ms   0.036ms
                    SettlementInstructionFactory.create    t+156.0ms   0.035ms  [nset=1153]
                    SettlementInstructionRepository.save   t+156.4ms   0.394ms
                      INSERT settlement_instructions (USD) t+156.7ms   0.088ms
                    SettlementInstructionFactory.create    t+156.8ms   0.011ms  [nset=1154]
                    SettlementInstructionRepository.save   t+156.8ms   0.170ms
                      INSERT settlement_instructions (EUR) t+157.0ms   0.033ms
                    Transaction.commit                     t+157.1ms   1.057ms
                      UPDATE matched_trades                t+157.4ms   0.060ms
                      UPDATE trades (A)                    t+157.7ms   0.138ms
                      UPDATE trades (B)                    t+158.0ms   0.111ms
                    TransactionLogRepository.save (COMMITTED) t+159.0ms 0.047ms
                    Transaction.commit                     t+159.0ms   0.276ms
                      UPDATE transaction_log → COMMITTED   t+159.1ms   0.104ms

                QueueBroker.complete (NETTING DONE)        t+159.4ms   0.555ms
                  UPDATE queue_messages → DONE             t+159.9ms   0.088ms
                QueueMessageTracing.markOutcome            t+160.0ms   0.005ms

────────────────────────────────────────────────────────────────────────────────
✓ DONE  t+160.0ms
```

---

## Timing Breakdown

| Stage              | Start     | Duration | Queue Wait |
|--------------------|-----------|----------|------------|
| HTTP               | t+0.0ms   | 4.9ms    | —          |
| → INGESTION queue  | t+37.0ms  | 6.1ms    | ~32ms      |
| → MATCHING queue   | t+41.7ms  | 22.3ms   | ~2ms       |
| → NETTING queue    | t+141.3ms | 18.7ms   | ~77ms      |
| **Total**          | —         | **160.0ms** | —       |

---

## Message Shape Per Component

### 1. POST /api/trades → INGESTION queue

**HTTP request body (FpML XML):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<tradeMessage>
  <header>
    <messageId>MSG-OTEL-1775072478A</messageId>
    <creationTimestamp>2026-04-01T10:00:00Z</creationTimestamp>
  </header>
  <trade>
    <tradeId>TRD-OTEL-1775072478A</tradeId>
    <tradeType>SPOT</tradeType>
    <party1><partyId>ALPHA_BANK</partyId><role>BUYER</role></party1>
    <party2><partyId>BETA_BANK</partyId><role>SELLER</role></party2>
    <currencyPair>
      <currency1>USD</currency1><amount1>2000000.00</amount1>
      <currency2>EUR</currency2><amount2>1840000.00</amount2>
      <exchangeRate>1.0869565</exchangeRate>
    </currencyPair>
    <valueDate>2026-12-01</valueDate>
  </trade>
</tradeMessage>
```

**QueueMessage persisted (INGESTION queue):**
```
queueName:    INGESTION
payload:      <full FpML XML above>
status:       NEW → PROCESSING → DONE
traceContext: "00-88188a467602bc09c60e697997a8a237-<spanId>-01"  ← W3C traceparent
attempts:     0
workerName:   ingestion-worker-N
```

---

### 2. TradeIngestionService → MATCHING queue

**What ingestion produces — Trade entity saved:**
```json
{
  "id": 3571,
  "tradeId": "TRD-OTEL-1775072478A",
  "messageId": "MSG-OTEL-1775072478A",
  "counterparty1": "ALPHA_BANK",
  "counterparty2": "BETA_BANK",
  "role1": "BUYER",
  "role2": "SELLER",
  "currency1": "USD",
  "amount1": 2000000.00,
  "currency2": "EUR",
  "amount2": 1840000.00,
  "exchangeRate": 1.0869565,
  "valueDate": "2026-12-01",
  "tradeType": "SPOT",
  "status": "VALIDATED"
}
```

**QueueMessage published to MATCHING:**
```
queueName:    MATCHING
payload:      {"tradeId": 3571}
traceContext: "00-88188a467602bc09c60e697997a8a237-<spanId>-01"
```

---

### 3. TradeMatchingEngine → NETTING queue

**What matching produces — MatchedTrade entity saved:**
```json
{
  "id": 658,
  "trade1Id": 3571,
  "trade2Id": 3572,
  "counterparty1": "ALPHA_BANK",
  "counterparty2": "BETA_BANK",
  "currency1": "USD",
  "currency2": "EUR",
  "valueDate": "2026-12-01",
  "status": "MATCHED"
}
```

**QueueMessage published to NETTING:**
```
queueName:    NETTING
payload:      {"matchedTradeId": 658}
traceContext: "00-88188a467602bc09c60e697997a8a237-<spanId>-01"
```

---

### 4. TwoPhaseCommitCoordinator — 2PC protocol messages

**Phase 1 — ParticipantVote (×2, one per participant):**
```json
{ "transactionId": "2PC-6034aa53", "participant": "NettingCalculator",   "voteStatus": "VOTE_COMMIT" }
{ "transactionId": "2PC-6034aa53", "participant": "SettlementInstructor","voteStatus": "VOTE_COMMIT" }
```

**TransactionLog state machine:**
```
INITIATED → (after votes) → COMMITTED
```

---

### 5. NettingCalculator → NettingSet entities (×2)

```json
{ "id": 1153, "matchedTradeId": 658, "counterparty1": "ALPHA_BANK", "counterparty2": "BETA_BANK",
  "currency": "USD", "netAmount": 2000000.0000, "valueDate": "2026-12-01" }

{ "id": 1154, "matchedTradeId": 658, "counterparty1": "ALPHA_BANK", "counterparty2": "BETA_BANK",
  "currency": "EUR", "netAmount": 1840000.0000, "valueDate": "2026-12-01" }
```

---

### 6. SettlementInstructor → SettlementInstruction entities (×2)

```json
{ "nettingSetId": 1153, "currency": "USD", "amount": 2000000.0000,
  "payerParty": "BETA_BANK", "receiverParty": "ALPHA_BANK", "status": "PENDING" }

{ "nettingSetId": 1154, "currency": "EUR", "amount": 1840000.0000,
  "payerParty": "ALPHA_BANK", "receiverParty": "BETA_BANK", "status": "PENDING" }
```

---

## OTel Span Attributes Per Component

| Component                        | `component.stage`  | `component.kind` | Key correlation attributes                          |
|----------------------------------|-------------|------------------|-----------------------------------------------------|
| `POST /api/trades`               | —           | SERVER           | http.method, http.status_code                       |
| `TradeSubmissionController`      | HTTP        | controller       | trade.id, message.id                                |
| `QueueBroker.publish`            | OTHER       | service          | queue.name, trade.id, message.id                    |
| `QueueMessage.process`           | —           | CONSUMER         | queue.name, trade.id, message.id, processing.outcome|
| `TradeIngestionService`          | INGESTION   | service          | trade.id, message.id                                |
| `TradeXmlParser`                 | INGESTION   | service          | trade.id, message.id                                |
| `CurrencyValidationService`      | INGESTION   | service          | —                                                   |
| `TradeRepository.save`           | DATABASE    | repository       | trade.id, message.id                                |
| `TradeMatchingEngine`            | MATCHING    | service          | trade.id (A+B), matched.trade.id                    |
| `MatchedTradeRepository.save`    | DATABASE    | repository       | matched.trade.id                                    |
| `NettingCalculator`              | NETTING     | service          | matched.trade.id                                    |
| `TwoPhaseCommitCoordinator`      | SETTLEMENT  | service          | matched.trade.id                                    |
| `NettingSetRepository.save`      | DATABASE    | repository       | matched.trade.id, netting.set.id                    |
| `SettlementInstructionFactory`   | SETTLEMENT  | service          | matched.trade.id, netting.set.id                    |
| `SettlementInstructionRepository`| DATABASE    | repository       | netting.set.id                                      |

---

## Architecture Diagram

```mermaid
flowchart TD
    Client(["🌐 Client\nPOST /api/trades\nFpML XML"])

    subgraph HTTP ["🔵 HTTP  ·  t+0ms  ·  4.9ms"]
        SC["TradeSubmissionController\n.submitTrade\ntrade.id · message.id"]
        QB0["QueueBroker.publish\n→ INGESTION"]
    end

    subgraph IQ ["INGESTION queue (H2)\npayload: raw FpML XML\ntraceContext: W3C traceparent"]
        IQM[["QueueMessage\nstatus: NEW→PROCESSING→DONE"]]
    end

    subgraph INGESTION ["🟢 INGESTION  ·  t+37ms  ·  6.1ms  ·  ingestion-worker"]
        QMP1["QueueMessage.process\nSpanKind: CONSUMER"]
        TIS["TradeIngestionService\n.processTradeXml"]
        XML["TradeXmlParser.parse"]
        VAL["TradeValidator.validate\nCurrencyValidationService ×2"]
        MAP["TradeEntityMapper.mapToTrade"]
        TR1["TradeRepository.save\nINSERT trades → id=3571"]
        QB1["QueueBroker.publish\n→ MATCHING"]
    end

    subgraph MQ ["MATCHING queue (H2)\npayload: {\"tradeId\": 3571}\ntraceContext: W3C traceparent"]
        MQM[["QueueMessage\nstatus: NEW→PROCESSING→DONE"]]
    end

    subgraph MATCHING ["🟡 MATCHING  ·  t+41.7ms  ·  22.3ms  ·  matching-worker"]
        QMP2["QueueMessage.process\nSpanKind: CONSUMER"]
        TME["TradeMatchingEngine\n.processMatchingMessage"]
        FIND["TradeRepository.findMatchCandidate\n→ finds TRD-OTEL-1775072478B"]
        MATCH["MatchedTradeFactory.create\nMatchedTradeRepository.save\n→ matched_trades id=658"]
        UPD["TradeRepository.save ×2\nA+B → MATCHED\nTransaction.commit 10.3ms"]
        QB2["QueueBroker.publish\n→ NETTING"]
    end

    subgraph NQ ["NETTING queue (H2)\npayload: {\"matchedTradeId\": 658}\ntraceContext: W3C traceparent"]
        NQM[["QueueMessage\nstatus: NEW→PROCESSING→DONE"]]
    end

    subgraph NETTING ["🔴 NETTING+2PC+SETTLEMENT  ·  t+141.3ms  ·  18.7ms  ·  netting-worker"]
        QMP3["QueueMessage.process\nSpanKind: CONSUMER"]
        NC["NettingCalculator\n.processNettingMessage"]

        subgraph TPC ["TwoPhaseCommitCoordinator.executeTransaction"]
            subgraph P1 ["Phase 1 — PREPARE"]
                TL1["TransactionLog INSERT\n(INITIATED)"]
                PV1["ParticipantVoteRepository.save\nNettingCalc → VOTE_COMMIT"]
                PV2["ParticipantVoteRepository.save\nSettleInstr → VOTE_COMMIT"]
            end
            subgraph P2 ["Phase 2 — COMMIT"]
                NS1["NettingSetRepository.save\nid=1153 · USD · 2,000,000"]
                NS2["NettingSetRepository.save\nid=1154 · EUR · 1,840,000"]
                MT2["MatchedTradeRepository.save\n→ NETTED"]
                TR2["TradeRepository.save ×2\nA+B → NETTED"]
                SI1["SettlementInstructionRepository.save\nnset=1153 · USD · BETA pays ALPHA"]
                SI2["SettlementInstructionRepository.save\nnset=1154 · EUR · ALPHA pays BETA"]
                TL2["TransactionLog UPDATE\n→ COMMITTED"]
            end
        end
    end

    DB[("🗄️ H2 coredb\ntrades\nmatched_trades\nnetting_sets\nsettlement_instructions\nparticipant_votes\ntransaction_log\nqueue_messages")]

    Client -->|"FpML XML"| SC
    SC --> QB0
    QB0 -->|"INSERT"| IQM
    IQM -->|"ingestion-worker claims"| QMP1
    QMP1 --> TIS
    TIS --> XML --> VAL --> MAP --> TR1 --> QB1
    QB1 -->|"INSERT {tradeId:3571}"| MQM
    MQM -->|"matching-worker claims"| QMP2
    QMP2 --> TME --> FIND --> MATCH --> UPD --> QB2
    QB2 -->|"INSERT {matchedTradeId:658}"| NQM
    NQM -->|"netting-worker claims"| QMP3
    QMP3 --> NC --> TPC
    P1 --> P2

    TR1 -->|"INSERT trades"| DB
    MATCH -->|"INSERT matched_trades"| DB
    NS1 & NS2 -->|"INSERT netting_sets"| DB
    SI1 & SI2 -->|"INSERT settlement_instructions"| DB
    PV1 & PV2 -->|"INSERT participant_votes"| DB
    TL1 & TL2 -->|"INSERT/UPDATE transaction_log"| DB

    style HTTP fill:#eff6ff,stroke:#3b82f6,stroke-width:2px
    style INGESTION fill:#f0fdf4,stroke:#16a34a,stroke-width:2px
    style MATCHING fill:#fefce8,stroke:#ca8a04,stroke-width:2px
    style NETTING fill:#fff1f2,stroke:#e11d48,stroke-width:2px
    style P1 fill:#fef3c7,stroke:#d97706,stroke-width:1.5px
    style P2 fill:#fce7f3,stroke:#db2777,stroke-width:1.5px
    style IQ fill:#e0f2fe,stroke:#0284c7,stroke-dasharray:5 5
    style MQ fill:#fef9c3,stroke:#a16207,stroke-dasharray:5 5
    style NQ fill:#ffe4e6,stroke:#be123c,stroke-dasharray:5 5
    style DB fill:#f1f5f9,stroke:#475569
```

---

## Trace Context Propagation

```
traceID: 88188a467602bc09c60e697997a8a237  (shared across ALL 195 spans)

 HTTP thread                INGESTION worker           MATCHING worker            NETTING worker
 ──────────────             ────────────────           ───────────────            ──────────────
 POST /api/trades           QueueMessage.process       QueueMessage.process       QueueMessage.process
 └─ submitTrade             (SpanKind: CONSUMER)       (SpanKind: CONSUMER)       (SpanKind: CONSUMER)
      └─ QB.publish()  ──── extracts traceparent ──── extracts traceparent ──── extracts traceparent
           writes            from traceContext           from traceContext           from traceContext
           traceparent       field in QueueMessage       field in QueueMessage       field in QueueMessage
           to QueueMessage
           .traceContext

Format: "00-88188a467602bc09c60e697997a8a237-<parentSpanId>-01"
```
