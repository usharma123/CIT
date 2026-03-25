# CLSNet Mock (`mocknet/`)

Mock CLSNet-style bilateral FX payment netting pipeline: Spring Boot, H2, and a durable DB-backed message broker. All code and docs in this README refer to the [`mocknet/`](./mocknet/) Maven module.

## Overview

Trades are submitted as FpML-like XML, written to a durable ingestion queue, validated and stored, matched into bilateral pairs, then run through a two-phase commit that atomically creates netting sets and settlement instructions. Failed messages are classified, retried where possible, and routed to a dead-letter queue when retries are exhausted. OpenTelemetry tracing covers every queue stage and component method. Everything runs in one Spring Boot application.

### Components

| Component | Role |
|-----------|------|
| `TradeSubmissionController` | HTTP entry point — accepts FpML XML, enqueues on `INGESTION` |
| `TradeIngestionService` | Parse, validate, persist trades; advance to `MATCHING` queue |
| `CurrencyValidationService` | Checks currencies against a configurable allow-list |
| `TradeMatchingEngine` | Pair compatible buyer/seller trades with pessimistic locking |
| `NettingCalculator` | Netting stage — delegates to the 2PC coordinator |
| `NettingCutoffService` | Per-currency settlement cutoff times |
| `TwoPhaseCommitCoordinator` | Atomic prepare/commit: creates netting sets and settlement instructions |
| `SettlementInstructor` | Optional settlement worker on `SETTLEMENT` queue; primary path creates instructions during commit |
| `QueueBroker` | Durable queue manager — claim, complete, retry, dead-letter |
| `FailureClassifier` | Categorises exceptions into retryable/non-retryable failure reasons |
| `QueueMessageTracing` | OpenTelemetry spans for queue message lifecycle |
| `ComponentTracingAspect` | AOP tracing across controllers, services, and repositories |
| `StatusController` | Read-only endpoints: pipeline state, queues, transaction log, votes |

## System structure

The diagram below shows the four pipeline stages (ingestion → matching → netting → 2PC commit), the error-handling path through the dead-letter queue, and the observability layer.

```mermaid
flowchart TB
    classDef queue fill:#fff7ed,stroke:#ea580c,stroke-width:2px,color:#0f172a
    classDef service fill:#ecfdf5,stroke:#059669,stroke-width:1.5px,color:#0f172a
    classDef repo fill:#fdf2f8,stroke:#db2777,stroke-width:1.5px,color:#0f172a
    classDef api fill:#eff6ff,stroke:#2563eb,stroke-width:2px,color:#0f172a
    classDef coord fill:#f5f3ff,stroke:#7c3aed,stroke-width:2px,color:#0f172a
    classDef read fill:#f8fafc,stroke:#475569,stroke-width:1.5px,color:#0f172a
    classDef ext fill:#ffffff,stroke:#94a3b8,stroke-width:1.5px,color:#0f172a
    classDef db fill:#fffbeb,stroke:#d97706,stroke-width:2px,color:#0f172a
    classDef dlq fill:#fef2f2,stroke:#dc2626,stroke-width:2px,color:#0f172a
    classDef trace fill:#fefce8,stroke:#ca8a04,stroke-width:1.5px,color:#0f172a

    Client([Client / upstream]):::ext -->|FpML XML| API["TradeSubmissionController<br/>POST /api/trades"]:::api

    subgraph APP["Spring Boot — single deployable"]
        direction TB

        API -->|publish| IQ["INGESTION queue"]:::queue

        subgraph S1["1 · Ingestion"]
            direction TB
            IQ -->|claim| TIS[TradeIngestionService]:::service
            TIS --> CVS[CurrencyValidationService]:::service
            TIS -->|persist| TR[(TradeRepository)]:::repo
            TIS -->|publish| MQ["MATCHING queue"]:::queue
        end

        subgraph S2["2 · Matching"]
            direction TB
            MQ -->|claim| TME[TradeMatchingEngine]:::service
            TME -->|persist| MTR[(MatchedTradeRepository)]:::repo
            TME -->|update status| TR
            TME -->|publish| NQ["NETTING queue"]:::queue
        end

        subgraph S3["3 · Netting + 2PC"]
            direction TB
            NQ -->|claim| NC[NettingCalculator]:::service
            NC --> NCS[NettingCutoffService]:::service
            NC --> TPC[TwoPhaseCommitCoordinator]:::coord
        end

        subgraph S4["4 · Atomic commit"]
            direction LR
            TPC -->|votes| TVR[(ParticipantVoteRepository)]:::repo
            TPC -->|audit| TLR[(TransactionLogRepository)]:::repo
            TPC -->|create| NSR[(NettingSetRepository)]:::repo
            TPC -->|create| SIR[(SettlementInstructionRepository)]:::repo
            TPC -->|update| MTR
            TPC -->|update| TR
        end

        subgraph S6["Settlement (optional queue path)"]
            direction TB
            SQ["SETTLEMENT queue"]:::queue -->|claim| SI[SettlementInstructor]:::service
            SI --> NSR
            SI --> SIR
        end

        subgraph ERR["Error handling"]
            direction LR
            FC[FailureClassifier]:::service
            DLQ["DEAD_LETTER queue"]:::dlq
            FC -->|non-retryable /<br/>max attempts| DLQ
        end

        subgraph OBS["Observability"]
            direction TB
            SC["StatusController<br/>GET /api/status · queues · logs"]:::read
            QMT[QueueMessageTracing]:::trace
            CTA[ComponentTracingAspect]:::trace
        end

        TIS & TME & NC -->|on failure| FC
        FC -->|retryable| IQ & MQ & NQ

        SC -.->|read| TR & MTR & NSR & SIR & TLR & TVR
    end

    H2[(H2 file DB<br/>./data/coredb)]:::db

    TR & MTR & NSR & SIR & TLR & TVR -.->|JPA| H2
    IQ & MQ & NQ & SQ & DLQ -.->|durable broker| H2
```

## Data flow

```mermaid
sequenceDiagram
    participant C as Client
    participant API as TradeSubmissionController
    participant IQ as INGESTION queue
    participant TIS as TradeIngestionService
    participant MQ as MATCHING queue
    participant TME as TradeMatchingEngine
    participant NQ as NETTING queue
    participant NC as NettingCalculator
    participant TPC as TwoPhaseCommitCoordinator
    participant DB as H2 Database
    participant DLQ as DEAD_LETTER queue

    C->>API: POST /api/trades (XML)
    API->>IQ: publish(payload)
    API-->>C: 202 Accepted

    Note over IQ,TIS: Stage 1 — Ingestion
    IQ->>TIS: claim message
    TIS->>TIS: parse XML, validate fields & currency
    alt valid trade
        TIS->>DB: persist Trade (VALIDATED)
        TIS->>MQ: publish(tradeId)
        TIS->>IQ: complete message
    else soft reject (bad currency/amount)
        TIS->>DB: persist Trade (REJECTED)
        TIS->>IQ: complete message
    else hard fail (invalid XML/missing fields)
        TIS->>IQ: fail message
        Note over IQ,DLQ: retry up to 3×, then DLQ
    end

    Note over MQ,TME: Stage 2 — Matching
    MQ->>TME: claim message
    TME->>DB: find opposite-side trade (SELECT FOR UPDATE)
    alt match found
        TME->>DB: create MatchedTrade, set trades MATCHED
        TME->>NQ: publish(matchedTradeId)
        TME->>MQ: complete message
    else no match yet
        TME->>MQ: complete message (awaits counterparty)
    end

    Note over NQ,TPC: Stage 3 — Netting + 2PC
    NQ->>NC: claim message
    NC->>TPC: executeTransaction(matchedTradeId)

    Note over TPC,DB: Phase 1 — Prepare
    TPC->>DB: create TransactionLog (INITIATED)
    TPC->>DB: NettingCalculator vote (COMMIT/ABORT)
    TPC->>DB: SettlementInstructor vote (COMMIT/ABORT)

    alt all vote COMMIT
        Note over TPC,DB: Phase 2 — Commit
        TPC->>DB: create NettingSets + SettlementInstructions
        TPC->>DB: set trades NETTED, log COMMITTED
        TPC->>NQ: complete message
    else any vote ABORT
        TPC->>DB: log ABORTED
        TPC->>NQ: fail message (retryable)
    end
```

## Queue system

All queues are backed by the `queue_messages` table with optimistic locking (`@Version`).

| Queue | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `INGESTION` | TradeSubmissionController | TradeIngestionService | Raw FpML XML |
| `MATCHING` | TradeIngestionService | TradeMatchingEngine | `{"tradeId": <id>}` |
| `NETTING` | TradeMatchingEngine | NettingCalculator | `{"matchedTradeId": <id>}` |
| `SETTLEMENT` | (optional path) | SettlementInstructor | settlement payload |
| `DEAD_LETTER` | QueueBroker (on final failure) | — (manual recovery) | original payload + error context |

**Message lifecycle:** `NEW` → `PROCESSING` → `DONE` or `FAILED`

**Retry policy:** up to 3 attempts, 500 ms fixed backoff, 30 s stale-claim timeout. Retryable errors (concurrency conflicts, transient DB failures) are rescheduled. Non-retryable errors (invalid XML, missing fields, data integrity) go to DLQ immediately.

**DLQ payload** includes `originalQueue`, `originalPayload`, `attempts`, `reasonCode`, `errorMessage`, and `failedAt`.

## Failure classification

`FailureClassifier` maps exceptions to a `FailureReason` code and a retryability flag:

| Exception type | Reason code | Retryable |
|---------------|-------------|-----------|
| `OptimisticLock` / `PessimisticLock` / `Deadlock` | `CONCURRENCY_CONFLICT` | yes |
| `TransientDataAccessException` | `TRANSIENT_DATA_ACCESS` | yes |
| `DataIntegrityViolationException` | `DATA_INTEGRITY_VIOLATION` | no |
| XML parse errors | `INVALID_XML` | no |
| Missing tradeId / party / currency / amount | `MISSING_*` | no |
| Unsupported currency, invalid amount | `UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT` | no |
| 2PC abort | `TWO_PHASE_COMMIT_ABORTED` | yes |

## Tracing

Two complementary OpenTelemetry layers run across the pipeline:

1. **QueueMessageTracing** — wraps each queue-message processing cycle in a `QueueMessage.process` span. Records `queue.name`, `worker.name`, correlation IDs (`tradeId`, `matchedTradeId`, `nettingSetId`), and the processing outcome (`completed`, `rejected`, `retried`, `failed` with `failure.reason_code`).

2. **ComponentTracingAspect** (AOP) — intercepts every public method in `controller/`, `service/`, and `repository/` packages. Tags each span with `cls.stage` (HTTP, INGESTION, MATCHING, NETTING, SETTLEMENT, DATABASE), `component.kind`, and any correlation IDs extracted from method arguments and return values.

## Processing flow (summary)

1. Client posts XML to `POST /api/trades`.
2. The controller stores a `QueueMessage` on the `INGESTION` queue.
3. `TradeIngestionService` claims the message, parses and validates, persists the trade, enqueues the trade id on `MATCHING`, marks ingestion `DONE`. Invalid trades are soft-rejected (persisted as `REJECTED`) or hard-failed (retried, then sent to DLQ).
4. `TradeMatchingEngine` claims a matching message, finds the opposite-side trade under pessimistic lock, creates `MatchedTrade`, enqueues on `NETTING`, marks matching `DONE`. If no counterparty exists yet the message completes silently.
5. `NettingCalculator` claims a netting message and opens work through `TwoPhaseCommitCoordinator`.
6. Prepare phase: `NettingCalculator` and `SettlementInstructor` validate and record `VOTE_COMMIT` or `VOTE_ABORT` in the participant-votes table.
7. On commit, the coordinator atomically creates `NettingSet` and `SettlementInstruction` rows, sets matched trades to `NETTED`, logs `COMMITTED`, and marks the netting message `DONE`. On abort, the message is retried.

## Layout under `mocknet/`

| Path | Role |
|------|------|
| [`mocknet/src/main/java/com/cit/clsnet/controller`](./mocknet/src/main/java/com/cit/clsnet/controller) | HTTP APIs |
| [`mocknet/src/main/java/com/cit/clsnet/service`](./mocknet/src/main/java/com/cit/clsnet/service) | Workers, `QueueBroker`, 2PC, validation, cutoff |
| [`mocknet/src/main/java/com/cit/clsnet/repository`](./mocknet/src/main/java/com/cit/clsnet/repository) | JPA repositories (including `QueueMessageRepository`) |
| [`mocknet/src/main/java/com/cit/clsnet/model`](./mocknet/src/main/java/com/cit/clsnet/model) | Entities and enums |
| [`mocknet/src/main/java/com/cit/clsnet/config`](./mocknet/src/main/java/com/cit/clsnet/config) | Queues, threads, `ClsNetProperties` |
| [`mocknet/src/main/java/com/cit/clsnet/xml`](./mocknet/src/main/java/com/cit/clsnet/xml) | FpML-style XML mapping |
| [`mocknet/src/main/resources`](./mocknet/src/main/resources) | `application.yml`, sample trades |
| [`mocknet/src/test/java/com/cit/clsnet`](./mocknet/src/test/java/com/cit/clsnet) | End-to-end and load tests |

Sample payloads: [`sample-trade-buy.xml`](./mocknet/src/main/resources/sample-trade-buy.xml), [`sample-trade-sell.xml`](./mocknet/src/main/resources/sample-trade-sell.xml).

## Run and test

From the repository root:

```bash
cd mocknet
mvn spring-boot:run
```

```bash
cd mocknet
mvn test
```

From the repository root, Bootstrap can prepare tracing and open a local CLS trace viewer:

```bash
bun install
bun run dev run "Use oteltrace for mocknet."
bash .bootstrap/otel/mocknet/start-jaeger.sh
bash .bootstrap/otel/mocknet/run-with-otel.sh
bun run dev run "Use traceview for mocknet and open the local viewer."
```

`traceview` writes local artifacts under `.bootstrap/traceview/mocknet/` and serves a localhost-only HTML viewer that polls Jaeger and renders CLS stages from the traces it finds.

Defaults (see [`mocknet/src/main/resources/application.yml`](./mocknet/src/main/resources/application.yml)):

- Java **17**, Spring Boot **3.2.5** ([`mocknet/pom.xml`](./mocknet/pom.xml))
- H2 file DB: `./data/coredb` (relative to the process working directory — use `mocknet/` when you run Maven there)
- Worker pool sizes under `clsnet.threads.*`
- Durable queues persisted as `queue_messages` via JPA
- H2 console enabled; HTTP port **8080**

## HTTP endpoints

- `POST /api/trades`
- `GET /api/status`
- `GET /api/trades`
- `GET /api/matched-trades`
- `GET /api/netting-sets`
- `GET /api/settlement-instructions`
- `GET /api/transaction-log`
- `GET /api/participant-votes`
- `GET /api/queues`
- `GET /api/queues/{queueName}/messages?status=...&limit=...`
