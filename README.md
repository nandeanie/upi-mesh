# UPI Offline Mesh

Send money with no internet. Encrypted payment packets hop device-to-device over Bluetooth until one phone gets connectivity — then the backend settles exactly once, no matter how many bridges upload simultaneously.

## Stack

Java 17 · Spring Boot 3.3 · PostgreSQL · RSA-2048 + AES-256-GCM · JUnit 5

## Run locally

```bash
mvn spring-boot:run
```

Opens at `http://localhost:8080` — uses H2 in-memory DB, no setup needed.

## Dashboard

The UI at `/` is a thin client over the REST API — every account, device, balance, counter and result on screen comes from the backend (nothing is hardcoded).

| Section | What it does | Backed by |
|---|---|---|
| Live stats | Settled, duplicates dropped, volume settled, cache size | `GET /api/audit`, `GET /api/mesh/state` |
| Demo console | Compose → inject → gossip → flush, or one-click **Run full demo**; live stepper | `POST /api/demo/send`, `/api/mesh/gossip`, `/api/mesh/flush`, `/api/mesh/reset` |
| Network topology + devices | Live mesh graph; click a phone to send from it; toggle 4G to change who bridges | `GET /api/mesh/state`, `POST /api/mesh/devices/{id}/internet` |
| Accounts | Balances with last-change delta; sender/receiver pickers | `GET /api/accounts` |
| Packet inspector | What a relaying phone sees (ciphertext + TTL only) and the backend's decision | `POST /api/demo/send` |
| Ledger | Filter, search, receipt modal with packet hash, CSV export | `GET /api/audit` |
| Activity log | Real backend events (inject, gossip, settle, duplicate, tamper…) | `GET /api/events` |
| Security lab | Tamper, replay, N-bridge race, overdraft | `POST /api/demo/tamper`, `/api/demo/concurrent-upload`, `/api/mesh/flush` |
| Full reset | Restores balances, clears ledger + counters (asks for the API key) | `POST /api/demo/reset-full` |

The API key is never embedded in the page: click **API key** in the sidebar (or trigger Full reset) and enter the value of `BRIDGE_API_KEY` — it is kept in `sessionStorage` for that tab only. Local-dev default is `demo-key`.

## How it works

```
Sender (offline)
  → encrypts PaymentInstruction with RSA-OAEP + AES-256-GCM
  → wraps in MeshPacket with TTL
  → gossips to nearby phones via Bluetooth

Bridge phone (gets internet)
  → POST /api/bridge/ingest

Backend:
  1. SHA-256(ciphertext) → idempotency check (putIfAbsent)
  2. RSA-OAEP decrypt AES key
  3. AES-GCM decrypt + verify auth tag
  4. Freshness check (signedAt within 24h)
  5. @Transactional PESSIMISTIC_WRITE debit + credit
```

## Exactly-once settlement

4-layer defence against concurrent bridge uploads:

| Layer | Mechanism |
|---|---|
| 1 | `ConcurrentHashMap.putIfAbsent` — in-memory, microseconds |
| 2 | `PESSIMISTIC_WRITE` DB lock on both accounts |
| 3 | `@Version` optimistic lock on Account entity |
| 4 | `UNIQUE INDEX` on `transactions.packet_hash` |

## API

| Method | Path | Auth |
|---|---|---|
| GET | `/api/server-key` | — |
| POST | `/api/bridge/ingest` | Bearer token |
| GET | `/api/accounts` | — |
| GET | `/api/transactions` | — |
| GET | `/api/audit` | — |
| GET | `/api/events?limit=50` | — |
| GET | `/api/health` | — |
| POST | `/api/demo/send` | — |
| POST | `/api/demo/run-full` | — |
| POST | `/api/demo/tamper` | — |
| POST | `/api/demo/concurrent-upload` | — |
| POST | `/api/demo/reset-full` | Bearer token |
| POST | `/api/mesh/gossip` | — |
| POST | `/api/mesh/flush` | — |
| POST | `/api/mesh/reset` | — |
| POST | `/api/mesh/devices/{id}/internet` | — |
| GET | `/api/mesh/state` | — |

`/api/audit` returns `summary` (`totalSettled`, `totalRejected`, `totalInvalid`, `duplicatesDropped`, `volumeSettled`, `idempotencyCacheSize`) plus `recentTransactions`. Invalid and duplicate packets are counted in memory rather than written to the ledger. `POST /api/demo/reset-full` now also clears the ledger and counters so they always agree with the restored balances.

## Tests

```bash
mvn test
```

Unit tests cover: exactly-once concurrency (3-thread), tamper detection, encrypt/decrypt roundtrip, insufficient funds, idempotency hash stability, distinct payment nonces. `ApiControllerIntegrationTest` (MockMvc) covers every REST endpoint, including the events feed, device toggle, tamper and concurrent-upload demos.

## Deploy (Render)

1. Set env vars: `SPRING_PROFILES_ACTIVE=railway`, `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `BRIDGE_API_KEY`
2. Add a PostgreSQL service in Render
3. Build: `mvn clean package -DskipTests`
4. Start: `java -Dserver.port=$PORT -Dspring.profiles.active=railway -jar target/upi-offline-mesh-1.0.0.jar`
