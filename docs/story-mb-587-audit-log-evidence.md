# MB-587 Administration Audit Log Ingestion & Durability Evidence

MB-587 implements cross-service administrative audit logging and operational audit visibility within Identity Service.

## Authority and Data Boundary

- Identity owns the administrative security audit projection (`security_audit_event`), the browsing endpoint (`GET /api/v1/admin/audit-events`), and the streaming CSV export endpoint (`GET /api/v1/admin/audit-events/export`).
- Cross-service audit events are ingested from the Kafka topic `mentalbridge.admin.audit-event.v1`.
- Privacy & Minimization:
  - Ingestion enforces a strict schema v1 allowlist. Unexpected top-level fields (e.g., `chatBody`, `rawJournal`, `credentials`) are rejected fail-closed to prevent sensitive data ingress.
  - Reasons, passwords, tokens, and private clinical payloads are never exposed.
  - Target and actor references to deleted or missing accounts are projected only as stable SHA-256 tombstones (`tombstone:<sha256>`), ensuring subject privacy and referential integrity.
- Authorization:
  - Both browse and export endpoints strictly enforce the `ADMIN` role. Non-admin and unauthenticated requests are rejected fail-closed (`401`/`403`).

## Retain-and-Retry Durability Policy (Strategy 1)

In accordance with architectural requirements for durable audit delivery:

1. **Listener Exception Propagation**:
   - `AdministrationAuditEventConsumer.onMessage()` propagates all database persistence errors (transient outages, deadlocks, connectivity losses) to the Kafka listener container error handler.
   - Idempotency: Duplicate events are safely absorbed via `SecurityAuditEventRepository.insertIfAbsent()` using native `INSERT ... ON CONFLICT (id) DO NOTHING` and logged at INFO level.

2. **Durable Error Handler Configuration & Finite Retry Elimination**:
   - `AdministrationAuditKafkaConfiguration` registers `administrationAuditListenerContainerFactory` with explicit unlimited retention-and-retry:
     - `errorHandler.setCommitRecovered(false)`: Prevents offset commit on persistence failure under any circumstances.
     - `errorHandler.setAckAfterHandle(false)`: Prevents acknowledgment of failed records.
     - `errorHandler.setSeekAfterError(true)`: Seeks consumer partition back to the failed offset.
     - Recoverer is guarded fail-closed: if ever invoked, it throws `IllegalStateException` to prevent log-and-discard.
     - **Unsafe Finite Mode Removal**: Any attempt to configure a finite positive `max-attempts` (e.g. `max-attempts=5` or `0`) is rejected at application startup with `IllegalArgumentException`, failing fast before any records can be consumed.
     - Configuration requires `mentalbridge.identity.audit-ingestion.max-attempts=-1` (or omitted default).

3. **Observability and Failure Logging**:
   - Consecutive persistence failures are logged with full contextual detail: topic name, partition, offset, and exception message at ERROR level.
   - Malformed JSON, schema violations, or unallowlisted event types are logged at WARN/DEBUG level and dismissed fail-closed without wedging the consumer queue.

4. **Recovery and Replay Mechanics**:
   - When database availability is restored, the consumer seamlessly resumes ingestion from the exact uncommitted offset without requiring administrative intervention or manual partition resets.
   - Restart resilience: If the container or service process restarts during an outage, Kafka retains the uncommitted consumer group offset and re-delivers the pending record immediately upon startup.

5. **Kafka Topic Retention Declaration**:
   - Declared in repository infrastructure [`compose.yml`](../compose.yml) under `kafka-init`:
     - Topic: `mentalbridge.admin.audit-event.v1`
     - Partitions: 3, Replication Factor: 1
     - `retention.ms=604800000` (7 days)
     - `cleanup.policy=delete`
     - `retention.bytes=-1` (unlimited bytes; default inherited, preventing premature log eviction)
     - Provisioning is idempotent: combines `--create --if-not-exists` with `kafka-configs.sh --alter ... --add-config retention.ms=604800000,cleanup.policy=delete` to guarantee existing topics are also configured.
   - Scope of Inspection: Local Compose and Testcontainers environments verified. In non-Compose production environments where external Kafka clusters are deployed, DevOps must ensure the cluster topic configuration applies `retention.ms=604800000`.

## Verification

- **Configuration Unit Tests**: `AdministrationAuditKafkaConfigurationTests` asserts that finite `max-attempts` (> 0 or 0) and non-positive `retry-interval` fail context startup fast, while default or explicit `-1` unlimited retry succeeds.
- **Node/Content Audit Checks**: Verified `content-admin-audit.test.ts` (187/187 tests pass).
- **PostgreSQL & Database Error Classification**: Tested `insertIfAbsent` idempotency and classification of integrity exceptions.
- **Runtime Durability Integration Tests**: `AdministrationAuditKafkaDurabilityIntegrationTests` verifies end-to-end Kafka delivery, continuous retry behavior exceeding retry budgets during simulated database outages, zero message loss, consumer container restart resilience, and successful exactly-once projection upon database recovery.
