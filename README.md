# UPI Offline Mesh

Send money with no internet. Encrypted payment packets hop device-to-device over Bluetooth until one phone gets connectivity — then the backend settles exactly once, no matter how many bridges upload simultaneously.

## Stack

Java 17 · Spring Boot 3.3 · PostgreSQL · RSA-2048 + AES-256-GCM · JUnit 5

## Run locally

```bash
mvn spring-boot:run
```

Opens at `http://localhost:8080` — uses H2 in-memory DB, no setup needed.

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
| POST | `/api/demo/send` | — |
| POST | `/api/demo/run-full` | — |
| POST | `/api/demo/reset-full` | Bearer token |
| POST | `/api/mesh/gossip` | — |
| POST | `/api/mesh/flush` | — |
| GET | `/api/mesh/state` | — |

## Tests

```bash
mvn test
```

6 tests covering: exactly-once concurrency (3-thread), tamper detection, encrypt/decrypt roundtrip, insufficient funds, idempotency hash stability, distinct payment nonces.

## Deploy (Render)

1. Set env vars: `SPRING_PROFILES_ACTIVE=railway`, `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `BRIDGE_API_KEY`
2. Add a PostgreSQL service in Render
3. Build: `mvn clean package -DskipTests`
4. Start: `java -Dserver.port=$PORT -Dspring.profiles.active=railway -jar target/upi-offline-mesh-1.0.0.jar`
