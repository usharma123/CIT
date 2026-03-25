# Mocknet CLS Pipeline Trace

```mermaid
sequenceDiagram
    participant Client
    participant Controller as TradeSubmissionController
    participant Broker as QueueBroker
    participant DB as H2 Database
    participant Ingestion as TradeIngestionService
    participant Validation as CurrencyValidationService
    participant Matching as MatchingService
    participant Netting as NettingService
    participant Settlement as SettlementService
    participant TwoPC as 2PC Coordinator

    Note over Client,TwoPC: Trade Submission (HTTP Stage)

    Client->>+Controller: POST /api/trades (TRD-001, XML)
    Controller->>+Broker: publish(INGESTION queue)
    Broker->>+DB: INSERT queue_messages
    DB-->>-Broker: OK
    Broker-->>-Controller: queued
    Controller-->>-Client: 202 Accepted (105ms cold)

    Client->>+Controller: POST /api/trades (TRD-002, XML)
    Controller->>+Broker: publish(INGESTION queue)
    Broker->>+DB: INSERT queue_messages
    DB-->>-Broker: OK
    Broker-->>-Controller: queued
    Controller-->>-Client: 202 Accepted (3.2ms warm)

    Note over Broker,DB: Async Pipeline Begins

    rect rgb(230, 245, 255)
        Note over Broker,DB: Ingestion Stage
        Broker->>Broker: claimNext(INGESTION)
        Broker->>+Ingestion: processTradeXml(TRD-001)
        Ingestion->>Validation: isSupported(USD) ✓
        Ingestion->>Validation: isSupported(EUR) ✓
        Ingestion->>+DB: INSERT trades (TRD-001)
        DB-->>-Ingestion: OK
        Ingestion-->>-Broker: done (13.5ms)

        Broker->>Broker: claimNext(INGESTION)
        Broker->>+Ingestion: processTradeXml(TRD-002)
        Ingestion->>Validation: isSupported(USD) ✓
        Ingestion->>Validation: isSupported(EUR) ✓
        Ingestion->>+DB: INSERT trades (TRD-002)
        DB-->>-Ingestion: OK
        Ingestion-->>-Broker: done (1.7ms)
    end

    rect rgb(230, 255, 230)
        Note over Broker,DB: Matching Stage
        Broker->>Broker: claimNext(MATCHING)
        Broker->>+Matching: match TRD-001 ↔ TRD-002
        Matching->>+DB: INSERT matched_trades
        DB-->>-Matching: OK
        Matching-->>-Broker: 1 matched pair
    end

    rect rgb(255, 245, 230)
        Note over Broker,DB: Netting Stage
        Broker->>Broker: claimNext(NETTING)
        Broker->>+Netting: net matched pair
        Netting->>+DB: INSERT netting_sets (×2)
        DB-->>-Netting: OK
        Netting-->>-Broker: 2 netting sets
    end

    rect rgb(245, 230, 255)
        Note over Broker,DB: Settlement Stage
        Broker->>Broker: claimNext(SETTLEMENT)
        Broker->>+Settlement: settle netting sets
        Settlement->>+DB: INSERT settlement_instructions (×2)
        DB-->>-Settlement: OK
        Settlement->>+TwoPC: initiate 2PC
        TwoPC->>DB: PREPARE (participant votes ×2)
        TwoPC->>DB: COMMIT
        TwoPC-->>-Settlement: COMMITTED
        Settlement-->>-Broker: done
    end

    Note over Client,TwoPC: Pipeline Complete

    Client->>+Controller: GET /api/status
    Controller->>+DB: SELECT counts
    DB-->>-Controller: results
    Controller-->>-Client: 200 {2 trades, 1 match, 2 netting sets, 2 settlements, COMMITTED}
```

Each colored `rect` corresponds to a separate trace root -- trace context does not propagate across the in-memory queue boundaries, which is why traceview sees individual traces per stage rather than one end-to-end span tree.
