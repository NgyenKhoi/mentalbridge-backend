# Realtime Service

NestJS 11 service on Node.js 24 that owns MentalBridge conversations, encrypted messages, WebSocket sessions and ephemeral presence.

## Current scope

- Socket.IO `/realtime` namespace with Identity-issued RS256 JWT authentication
- strict versioned command, acknowledgement, error and server-event envelopes
- Redis TTL presence and bounded per-account connection routing
- encrypted MongoDB message persistence before acknowledgement
- sender-scoped `clientMessageId` idempotency
- planned cursor-based REST message history, currently fail-closed in production
- liveness, readiness, Prometheus metrics, safe structured logs and correlation IDs
- disposable MongoDB and Redis integration tests

Conversation eligibility remains fail-closed because Consultation has not published its appointment eligibility OpenAPI contract. Tests inject a synthetic eligibility implementation; production has no unrestricted-chat fallback. The history OpenAPI operation is marked `planned` and returns `503` in production until that adapter exists. Appointment activation, cross-instance Redis fan-out, Kafka publication, receipts, moderation, attachments, tombstone policy and live notification delivery remain outside this baseline.

Socket authentication is valid only through the JWT expiry boundary. The gateway retains the verified expiry, rejects commands at or after it, emits `AUTHENTICATION_EXPIRED`, and disconnects the socket. Revocation before expiry is deferred to a separately contracted Identity integration.

Message acknowledgements use `liveDelivery: not_applicable` because this baseline cannot prove recipient delivery. An idempotent duplicate returns the original message acknowledgement without emitting another `message.created` event.

## Contracts and data

- REST: `../contracts/openapi/realtime-service-v1.yaml`
- WebSocket: `../contracts/websocket/realtime/`
- MongoDB migration: `migrations/001_realtime_message_foundation.cjs`

MongoDB is durable truth. Redis contains only expiring socket routing and presence metadata. Message bodies are encrypted with AES-256-GCM and are never written to logs, metrics, Redis or cross-service events.

## Commands

```powershell
npm ci
npm run dev
npm run format:check
npm run lint
npm run typecheck
npm test
npm run contract:check
npm run migration:check
npm run test:integration
npm run build
npm start
```

## Configuration

| Variable                                  | Required   | Default outside production  | Purpose                                           |
| ----------------------------------------- | ---------- | --------------------------- | ------------------------------------------------- |
| `REALTIME_PORT`                           | No         | `3004`                      | HTTP and Socket.IO port                           |
| `REALTIME_LOG_LEVEL`                      | No         | `info`                      | Pino log level                                    |
| `REALTIME_CORS_ORIGINS`                   | Production | empty/deny                  | Comma-separated client origins                    |
| `REALTIME_MONGODB_URI`                    | Production | `mongodb://localhost:27017` | Realtime-owned MongoDB server                     |
| `REALTIME_MONGODB_DATABASE`               | Production | `mentalbridge_realtime`     | Realtime-owned database                           |
| `REALTIME_MONGODB_CONNECTION_TIMEOUT_MS`  | No         | `2000`                      | MongoDB selection timeout                         |
| `REALTIME_REDIS_URL`                      | Production | `redis://localhost:6379`    | Ephemeral presence store                          |
| `REALTIME_REDIS_CONNECTION_TIMEOUT_MS`    | No         | `1000`                      | Redis connection timeout                          |
| `REALTIME_PRESENCE_TTL_SECONDS`           | No         | `45`                        | Socket presence expiry                            |
| `REALTIME_PRESENCE_HEARTBEAT_SECONDS`     | No         | `15`                        | Client heartbeat interval                         |
| `REALTIME_MAX_CONNECTIONS_PER_ACCOUNT`    | No         | `5`                         | Bounded active sockets per account                |
| `REALTIME_MAX_PAYLOAD_BYTES`              | No         | `16384`                     | Socket.IO packet and command limit                |
| `REALTIME_COMMAND_RATE_LIMIT`             | No         | `60`                        | Commands per socket window                        |
| `REALTIME_COMMAND_RATE_WINDOW_SECONDS`    | No         | `60`                        | Local socket rate window                          |
| `REALTIME_SHUTDOWN_TIMEOUT_MS`            | No         | `10000`                     | Graceful shutdown deadline                        |
| `REALTIME_MESSAGE_ENCRYPTION_KEY`         | Yes        | none                        | Base64-encoded 32-byte AES key                    |
| `REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION` | Yes        | none                        | Non-secret active key identifier                  |
| `REALTIME_MESSAGE_DECRYPTION_KEYS`        | No         | `{}`                        | JSON map of prior key versions to Base64 AES keys |
| `IDENTITY_JWT_ISSUER`                     | Yes        | none                        | Exact Identity issuer                             |
| `IDENTITY_JWT_AUDIENCE`                   | Yes        | none                        | Exact API audience                                |
| `IDENTITY_JWT_KEY_ID`                     | Yes        | none                        | Active public verification key ID                 |
| `IDENTITY_JWT_PUBLIC_KEY`                 | Yes        | none                        | Identity X.509 RSA public key                     |
| `IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS`    | No         | `60`                        | Bounded JWT clock tolerance                       |

Local `.env` loading is enabled only in development and never overrides real environment variables. Tests inject isolated configuration and do not read developer `.env` files.

The active encryption key is always added to the decryption keyring. During rotation, keep every still-referenced prior key in `REALTIME_MESSAGE_DECRYPTION_KEYS`, for example `{"manual-v1":"<base64-key>"}`, deploy the expanded keyring first, and only then change the active key/version. Removing a referenced version makes those messages intentionally undecryptable.

## Failure behavior

- MongoDB failure prevents a successful message acknowledgement and makes readiness fail.
- Redis failure reports degraded readiness and unknown presence; it does not erase durable messages or prevent REST history recovery.
- Missing Consultation eligibility fails subscribe, send and history closed.
- Duplicate logical sends return the original message; reuse with different content returns an idempotency conflict.
- Redis availability does not change an acknowledgement into a delivery claim; live delivery remains `not_applicable` until fan-out or receipts define provable semantics.
