# mocknet Trace Flows

Diagrams derived from live Jaeger span data after submitting `sample-trade-buy.xml` and `sample-trade-sell.xml` through the full pipeline.

---

## Component Flow

```mermaid
flowchart TD
    subgraph HTTP ["🔵  TRACE A — HTTP   ·   fc202f698ec96b61"]
        direction TB
        A1["<b>POST /api/trades</b>\nauto-instrumented · SpanKind SERVER"]
        A2["<b>TradeSubmissionController</b>\n.submitTrade"]
        A3["<b>QueueBroker</b>\n.publish"]
        A4["<b>QueueMessageRepository</b>\n.save"]
        A5[("<b>INSERT</b>\nqueue_messages")]
    end

    subgraph INGESTION ["🟢  TRACE B — INGESTION   ·   0934c26eebb9f509"]
        direction TB
        B0["<b>QueueMessage.process</b>\nCONSUMER root · setNoParent()"]
        B1["<b>TradeIngestionService</b>\n.processTradeXml"]
        B2["<b>CurrencyValidationService</b>\n.isSupported ×2"]
        B3["<b>TradeRepository</b>\n.save"]
        B4[("<b>INSERT</b>\ntrades")]
        B5["<b>QueueBroker</b>\n.publish"]
        B6["<b>QueueMessageRepository</b>\n.save"]
        B7[("<b>INSERT</b>\nqueue_messages")]
    end

    subgraph MATCHING ["🟡  TRACE C — MATCHING   ·   205f9f469331b0d0"]
        direction TB
        C0["<b>QueueMessage.process</b>\nCONSUMER root · setNoParent()"]
        C1["<b>TradeMatchingEngine</b>\n.processMatchingMessage"]
        C2["<b>TradeRepository</b>\n.findById"]
        C3[("<b>SELECT</b>\ntrades")]
        C4["<b>QueueBroker</b>\n.publish"]
        C5["<b>QueueMessageRepository</b>\n.save"]
        C6[("<b>INSERT</b>\nqueue_messages")]
    end

    subgraph NETTING ["🔴  TRACE D — NETTING + 2PC + SETTLEMENT   ·   6745dd3329f9240b"]
        direction TB
        D0["<b>QueueMessage.process</b>\nCONSUMER root · setNoParent()"]
        D1["<b>NettingCalculator</b>\n.processNettingMessage"]
        D2["<b>TwoPhaseCommitCoordinator</b>\n.executeTransaction"]
        subgraph PHASE1 ["Phase 1 — PREPARE"]
            D7["<b>ParticipantVoteRepository</b>\n.save ×2"]
            D8["<b>TransactionLogRepository</b>\n.save / find ×6"]
            DP[("<b>INSERT</b> participant_votes\n<b>INSERT / UPDATE</b> transaction_log")]
        end
        subgraph PHASE2 ["Phase 2 — COMMIT"]
            D3["<b>NettingSetRepository</b>\n.save ×2"]
            D4["<b>MatchedTradeRepository</b>\n.save"]
            D5["<b>TradeRepository</b>\n.save ×2"]
            D6["<b>SettlementInstructionRepository</b>\n.save ×2"]
            DC[("<b>INSERT</b> netting_sets\n<b>INSERT</b> settlement_instructions\n<b>UPDATE</b> trades · matched_trades\n<b>Transaction.commit</b>")]
        end
    end

    A1 --> A2 --> A3 --> A4 --> A5
    B0 --> B1
    B1 --> B2
    B1 --> B3 --> B4
    B1 --> B5 --> B6 --> B7
    C0 --> C1
    C1 --> C2 --> C3
    C1 --> C4 --> C5 --> C6
    D0 --> D1 --> D2
    D2 --> PHASE1
    D2 --> PHASE2
    D7 --> DP
    D8 --> DP
    D3 --> DC
    D4 --> DC
    D5 --> DC
    D6 --> DC

    A3 -- "enqueue XML\n━━ INGESTION queue ━━" --> B0
    B5 -- "enqueue tradeId\n━━ MATCHING queue ━━" --> C0
    C4 -- "enqueue matchedTradeId\n━━ NETTING queue ━━" --> D0

    style HTTP fill:#eff6ff,stroke:#3b82f6,stroke-width:2px,color:#1e3a5f
    style INGESTION fill:#f0fdf4,stroke:#16a34a,stroke-width:2px,color:#14532d
    style MATCHING fill:#fefce8,stroke:#ca8a04,stroke-width:2px,color:#713f12
    style NETTING fill:#fff1f2,stroke:#e11d48,stroke-width:2px,color:#881337
    style PHASE1 fill:#fef3c7,stroke:#d97706,stroke-width:1.5px,color:#78350f
    style PHASE2 fill:#fce7f3,stroke:#db2777,stroke-width:1.5px,color:#831843
    style A1 fill:#bfdbfe,stroke:#2563eb,color:#1e3a5f
    style A2 fill:#dbeafe,stroke:#3b82f6,color:#1e3a5f
    style A3 fill:#dbeafe,stroke:#3b82f6,color:#1e3a5f
    style A4 fill:#dbeafe,stroke:#3b82f6,color:#1e3a5f
    style A5 fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style B0 fill:#bbf7d0,stroke:#15803d,color:#14532d
    style B1 fill:#dcfce7,stroke:#16a34a,color:#14532d
    style B2 fill:#dcfce7,stroke:#16a34a,color:#14532d
    style B3 fill:#dcfce7,stroke:#16a34a,color:#14532d
    style B4 fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style B5 fill:#dcfce7,stroke:#16a34a,color:#14532d
    style B6 fill:#dcfce7,stroke:#16a34a,color:#14532d
    style B7 fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style C0 fill:#fef08a,stroke:#b45309,color:#713f12
    style C1 fill:#fef9c3,stroke:#ca8a04,color:#713f12
    style C2 fill:#fef9c3,stroke:#ca8a04,color:#713f12
    style C3 fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style C4 fill:#fef9c3,stroke:#ca8a04,color:#713f12
    style C5 fill:#fef9c3,stroke:#ca8a04,color:#713f12
    style C6 fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style D0 fill:#fecdd3,stroke:#be123c,color:#881337
    style D1 fill:#ffe4e6,stroke:#e11d48,color:#881337
    style D2 fill:#ffe4e6,stroke:#e11d48,color:#881337
    style D7 fill:#fde68a,stroke:#d97706,color:#78350f
    style D8 fill:#fde68a,stroke:#d97706,color:#78350f
    style DP fill:#e0e7ff,stroke:#6366f1,color:#312e81
    style D3 fill:#fce7f3,stroke:#db2777,color:#831843
    style D4 fill:#fce7f3,stroke:#db2777,color:#831843
    style D5 fill:#fce7f3,stroke:#db2777,color:#831843
    style D6 fill:#fce7f3,stroke:#db2777,color:#831843
    style DC fill:#e0e7ff,stroke:#6366f1,color:#312e81
```

---

## HTTP Sequence Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client as 🌐 Client
    participant Tomcat as Tomcat<br/>POST /api/trades
    participant SC as TradeSubmissionController<br/>.submitTrade
    participant QB1 as QueueBroker<br/>.publish
    participant QMR1 as QueueMessageRepository<br/>.save
    participant QB2 as QueueBroker<br/>.claimNext
    participant TIS as TradeIngestionService<br/>.processTradeXml
    participant CVS as CurrencyValidationService<br/>.isSupported
    participant TR1 as TradeRepository<br/>.save
    participant QB3 as QueueBroker<br/>.publish
    participant QMR2 as QueueMessageRepository<br/>.save
    participant QB4 as QueueBroker<br/>.claimNext
    participant TME as TradeMatchingEngine<br/>.processMatchingMessage
    participant TR2 as TradeRepository<br/>.findById
    participant QB5 as QueueBroker<br/>.publish
    participant QMR3 as QueueMessageRepository<br/>.save
    participant QB6 as QueueBroker<br/>.claimNext
    participant NC as NettingCalculator<br/>.processNettingMessage
    participant TPC as TwoPhaseCommitCoordinator<br/>.executeTransaction
    participant DB as 🗄️ H2 coredb

    rect rgb(219, 234, 254)
        note over Client,DB: TRACE A — HTTP  (fc202f698ec96b61)
        Client->>+Tomcat: POST /api/trades (XML body)
        Tomcat->>+SC: submitTrade(xmlPayload)
        SC->>+QB1: publish(INGESTION, xml)
        QB1->>+QMR1: save(QueueMessage)
        QMR1->>DB: INSERT queue_messages
        QMR1-->>-QB1: saved
        QB1-->>-SC: ok
        SC-->>-Tomcat: 202 Accepted
        Tomcat-->>-Client: HTTP 202
    end

    rect rgb(240, 253, 244)
        note over QB2,DB: TRACE B — INGESTION  (0934c26eebb9f509)  ·  ingestion-worker thread
        QB2->>DB: SELECT queue_messages (claimNext)
        QB2->>+TIS: processTradeXml(xml)
        TIS->>CVS: isSupported(USD)
        TIS->>CVS: isSupported(EUR)
        TIS->>+TR1: save(Trade)
        TR1->>DB: INSERT trades
        TR1-->>-TIS: Trade{id=1}
        TIS->>+QB3: publish(MATCHING, tradeId)
        QB3->>+QMR2: save(QueueMessage)
        QMR2->>DB: INSERT queue_messages
        QMR2-->>-QB3: saved
        QB3-->>-TIS: ok
        TIS->>DB: Transaction.commit
        TIS-->>-QB2: COMPLETED
        QB2->>DB: UPDATE queue_messages (complete)
    end

    rect rgb(254, 252, 232)
        note over QB4,DB: TRACE C — MATCHING  (205f9f469331b0d0)  ·  matching-worker thread
        QB4->>DB: SELECT queue_messages (claimNext)
        QB4->>+TME: processMatchingMessage(tradeId)
        TME->>+TR2: findById(tradeId)
        TR2->>DB: SELECT trades
        TR2-->>-TME: Trade (buy+sell pair matched)
        TME->>+QB5: publish(NETTING, matchedTradeId)
        QB5->>+QMR3: save(QueueMessage)
        QMR3->>DB: INSERT queue_messages
        QMR3-->>-QB5: saved
        QB5-->>-TME: ok
        TME->>DB: Transaction.commit
        TME-->>-QB4: COMPLETED
        QB4->>DB: UPDATE queue_messages (complete)
    end

    rect rgb(255, 241, 242)
        note over QB6,DB: TRACE D — NETTING + 2PC + SETTLEMENT  (6745dd3329f9240b)  ·  netting-worker thread
        QB6->>DB: SELECT queue_messages (claimNext)
        QB6->>+NC: processNettingMessage(matchedTradeId)
        NC->>+TPC: executeTransaction(matchedTradeId)
        note over TPC,DB: Phase 1 — PREPARE
        TPC->>DB: INSERT transaction_log (INITIATED)
        TPC->>DB: INSERT participant_votes ×2
        TPC->>DB: Transaction.commit
        TPC->>DB: SELECT transaction_log (verify votes)
        note over TPC,DB: Phase 2 — COMMIT
        TPC->>DB: INSERT netting_sets ×2
        TPC->>DB: Session.merge MatchedTrade
        TPC->>DB: Session.merge Trade ×2  (mark MATCHED)
        TPC->>DB: INSERT settlement_instructions ×2
        TPC->>DB: Transaction.commit
        TPC->>DB: UPDATE trades · UPDATE matched_trades
        TPC->>DB: UPDATE transaction_log (COMMITTED)
        TPC-->>-NC: committed
        NC-->>-QB6: COMPLETED
        QB6->>DB: UPDATE queue_messages (complete)
    end
```

---

## Rendered PNGs

| Diagram | File |
|---|---|
| Component Flow | `mocknet-component-flow.png` |
| HTTP Sequence Flow | `mocknet-http-flow.png` |
