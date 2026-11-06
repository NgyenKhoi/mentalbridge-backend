# Realtime Service

NestJS 11 service on Node.js 24 that owns appointment conversations/encrypted messages and authenticated WebSocket delivery. Community peer messages remain exclusively in Community PostgreSQL.

## Current scope

MB-689 implements the opt-in `/community` namespace under the accepted
[Community chat ADR](../docs/adr/0042-community-chat-contract.md). It fetches
current authorized owner projections without copying content to MongoDB, Kafka
or Redis. The appointment baseline below remains independent; Community setup,
protocol and recovery are documented in [COMMUNITY.md](COMMUNITY.md). MB-690
adds opt-in foreground presence with Community-owned viewer authorization,
Redis multi-device leases and cross-replica delivery. Its lease is distinct
from both appointment presence below and the Community socket routing lease.
MB-749 adds the Identity-authenticated native Community ticket exchange at
`POST /api/v1/community/socket-credentials`; the existing BFF exchange remains
unchanged. See [ADR 0043](../docs/adr/0043-native-community-credential-exchange.md)
and the native handoff in [COMMUNITY.md](COMMUNITY.md#native-mobile-exchange-mb-749).

- Socket.IO `/realtime` namespace with one-use, 30-second browser credentials bound to Identity-issued RS256 JWTs
- strict versioned command, acknowledgement, error and server-event envelopes
- Redis TTL presence and bounded per-account connection routing
- encrypted MongoDB message persistence before acknowledgement
- sender-scoped `clientMessageId` idempotency
- implemented, owner-authorized cursor-based REST message history
- liveness, readiness, Prometheus metrics, safe structured logs and correlation IDs
- disposable MongoDB/Redis chat regression and Content HTTP/PostgreSQL/Kafka/Redis notification integration tests

Consultation is authoritative for appointment participants, status and server-time chat windows. Realtime checks its eligibility projection for every subscribe, send and history operation and fails closed when Consultation is unavailable. Sending is restricted to confirmed `IN_APP_CHAT` appointments in `[scheduledStartAt, scheduledEndAt)`; the ten-minute waiting window is subscribe-only, and terminal history is read-only. Cross-instance Redis fan-out, Kafka publication, receipts, moderation, attachments, and tombstone policy remain outside the appointment chat baseline.

The browser exchanges its server-owned Identity session through a same-origin BFF for a random one-use ticket. Realtime stores a hashed ticket key and AES-GCM encrypted bearer in Redis, atomically consumes it with `GETDEL`, then retains the bearer only in socket memory through the original JWT expiry boundary. The gateway rejects commands at or after expiry, emits `AUTHENTICATION_EXPIRED`, and disconnects the socket. Revocation before expiry is deferred to a separately contracted Identity integration.

Message acknowledgements use `liveDelivery: not_applicable` because this baseline cannot prove recipient delivery. An idempotent duplicate returns the original message acknowledgement without emitting another `message.created` event.

## Specialist inbox signals (MB-800)

`REALTIME_SPECIALIST_LIVE_ENABLED=false` independently controls specialist
notification delivery on the existing authenticated `/realtime` namespace. When
enabled, one shared Kafka consumer group reads Content's committed
`mentalbridge.content.notification-created.v1` pointers and publishes minimized
routing metadata on Redis channel
`mentalbridge:realtime:specialist-notifications:v1`. Every replica checks its
local SPECIALIST sockets against Content's current
`GET /internal/v1/notifications/{notificationId}/live-eligibility`, forwarding
the socket's existing Identity bearer. It checks account, role, connection and
the original credential expiry again after the owner request.

The separate Socket.IO event `notification.created` carries only the stable
event identity and `payload.notificationId`. Existing `realtime.event` chat and
ready envelopes remain unchanged. No chat subscription or notification room is
required. Each device deduplicates successful emissions locally for at most five
minutes (256 entries), coalesces in-flight duplicates and bounds pending work at
32 events. Owner reads fail closed and retry at most three times within the
socket and event lifetime.

Redis failures use the ACKed retry topic
`mentalbridge.realtime.specialist-notification-retry.v1`, with successive
1-second, 5-second and 30-second delays. Paused retry partitions leave the main
topic available. Poison/exhausted records go to
`mentalbridge.realtime.specialist-notification-dead-letter.v1` as digests and
safe diagnostics only. Kafka and Redis recovery never changes Content's durable
`DELIVERED` meaning into a socket receipt or ACK.

The frontend consumer (MB-801) must refetch the inbox and absolute unread count
on the first `connection.ready`, every reconnect and coalesced signals; it must
reset stale pagination and reconcile read/delete changes. Signals have no replay
guarantee. Redis/subscriber loss and bounded overflow can miss an invalidation;
the owner REST list repairs it. Legacy v1 appointment events without an assigned
specialist remain unsupported and are never inferred or replayed.

## Contracts and data

- REST: `../contracts/openapi/realtime-service-v1.yaml`
- WebSocket: `../contracts/websocket/realtime/`
- MongoDB migration: `migrations/001_realtime_message_foundation.cjs`

MongoDB is durable truth. Redis contains only expiring socket routing and presence metadata. Message bodies are encrypted with AES-256-GCM and are never written to logs, metrics, Redis or cross-service events.

## Commands

The disposable MongoDB integration fixture sets
`GLIBC_TUNABLES=glibc.pthread.rseq=1` so glibc retains rseq registration and MongoDB
uses its supported per-thread allocator path. The GitHub Ubuntu 24.04 image
[`20261018.339.1`](https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20261018.339)
moved to kernel `7.0.0-1017-azure`; MongoDB documents a
[TCMalloc incompatibility on kernels 6.19–7.0.13](https://www.mongodb.com/docs/manual/release-notes/8.0/).
See [MongoDB's allocator explanation](https://www.mongodb.com/docs/manual/administration/tcmalloc-performance/)
and the [official image issue](https://github.com/docker-library/mongo/issues/765).
This setting affects only the disposable test container. It does not bypass
MongoDB's startup guard, change production configuration, remove readiness
checks or weaken the real MongoDB/Redis/Socket.IO integration assertions.

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

| Variable                                  | Required          | Default outside production  | Purpose                                                                         |
| ----------------------------------------- | ----------------- | --------------------------- | ------------------------------------------------------------------------------- |
| `REALTIME_PORT`                           | No                | `3004`                      | HTTP and Socket.IO port                                                         |
| `REALTIME_LOG_LEVEL`                      | No                | `info`                      | Pino log level                                                                  |
| `REALTIME_CORS_ORIGINS`                   | Production        | empty/deny                  | Comma-separated client origins                                                  |
| `REALTIME_MONGODB_URI`                    | Production        | `mongodb://localhost:27017` | Realtime-owned MongoDB server                                                   |
| `REALTIME_MONGODB_DATABASE`               | Production        | `mentalbridge_realtime`     | Realtime-owned database                                                         |
| `REALTIME_MONGODB_CONNECTION_TIMEOUT_MS`  | No                | `2000`                      | MongoDB selection timeout                                                       |
| `REALTIME_REDIS_URL`                      | Production        | `redis://localhost:6379`    | Ephemeral presence store                                                        |
| `REALTIME_REDIS_CONNECTION_TIMEOUT_MS`    | No                | `1000`                      | Redis connection timeout                                                        |
| `REALTIME_PRESENCE_TTL_SECONDS`           | No                | `45`                        | Socket presence expiry                                                          |
| `REALTIME_PRESENCE_HEARTBEAT_SECONDS`     | No                | `15`                        | Client heartbeat interval                                                       |
| `REALTIME_MAX_CONNECTIONS_PER_ACCOUNT`    | No                | `5`                         | Bounded active sockets per account                                              |
| `REALTIME_MAX_PAYLOAD_BYTES`              | No                | `16384`                     | Socket.IO packet and command limit                                              |
| `REALTIME_COMMAND_RATE_LIMIT`             | No                | `60`                        | Commands per socket window                                                      |
| `REALTIME_COMMAND_RATE_WINDOW_SECONDS`    | No                | `60`                        | Local socket rate window                                                        |
| `REALTIME_SHUTDOWN_TIMEOUT_MS`            | No                | `10000`                     | Graceful shutdown deadline                                                      |
| `REALTIME_MESSAGE_ENCRYPTION_KEY`         | Yes               | none                        | Base64-encoded 32-byte AES key                                                  |
| `REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION` | Yes               | none                        | Non-secret active key identifier                                                |
| `REALTIME_MESSAGE_DECRYPTION_KEYS`        | No                | `{}`                        | JSON map of prior key versions to Base64 AES keys                               |
| `IDENTITY_JWT_ISSUER`                     | Yes               | none                        | Exact Identity issuer                                                           |
| `IDENTITY_JWT_AUDIENCE`                   | Yes               | none                        | Exact API audience                                                              |
| `IDENTITY_JWT_KEY_ID`                     | Yes               | none                        | Active public verification key ID                                               |
| `IDENTITY_JWT_PUBLIC_KEY`                 | Yes               | none                        | Identity X.509 RSA public key                                                   |
| `IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS`    | No                | `60`                        | Bounded JWT clock tolerance                                                     |
| `CONSULTATION_BASE_URL`                   | No                | `http://localhost:8083`     | Consultation eligibility authority                                              |
| `CONSULTATION_TIMEOUT_MS`                 | No                | `1500`                      | Eligibility request timeout                                                     |
| `CONSULTATION_EVIDENCE_SERVICE_TOKEN`     | Yes               |                             | Shared token for trusted chat evidence writes                                   |
| `REALTIME_SPECIALIST_LIVE_ENABLED`        | No                | `false`                     | Enable specialist inbox consumer and socket delivery independently of Community |
| `KAFKA_BOOTSTRAP_SERVERS`                 | When live enabled | empty                       | Existing broker list for specialist main, retry and DLQ topics                  |
| `CONTENT_NOTIFICATION_SERVICE_URL`        | When live enabled | empty                       | Plain Content HTTP(S) base URL without credentials, path, query or fragment     |
| `CONTENT_NOTIFICATION_TIMEOUT_MS`         | No                | `2000`                      | Current owner-read deadline, bounded to 100–5000 ms                             |

Local `.env` loading is enabled only in development and never overrides real environment variables. Tests inject isolated configuration and do not read developer `.env` files.

The active encryption key is always added to the decryption keyring. During rotation, keep every still-referenced prior key in `REALTIME_MESSAGE_DECRYPTION_KEYS`, for example `{"manual-v1":"<base64-key>"}`, deploy the expanded keyring first, and only then change the active key/version. Removing a referenced version makes those messages intentionally undecryptable.

## Failure behavior

- MongoDB failure prevents a successful message acknowledgement and makes readiness fail.
- Redis failure reports degraded readiness and unknown presence; it does not erase durable messages or prevent REST history recovery.
- Missing Consultation eligibility fails subscribe, send and history closed.
- Duplicate logical sends return the original message; reuse with different content returns an idempotency conflict.
- Redis availability does not change an acknowledgement into a delivery claim; live delivery remains `not_applicable` until fan-out or receipts define provable semantics.
