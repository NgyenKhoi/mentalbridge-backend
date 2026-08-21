# MongoDB Collection Definitions

MongoDB stores variable, write-heavy content where document access follows an aggregate. PostgreSQL remains authoritative for identity, consent, scoring, risk, appointments, job state, and audit. MongoDB-owning Node.js services use the official MongoDB driver and apply collection validation, indexes, and controlled data changes through append-only `migrate-mongo` migrations; application startup must not mutate schemas implicitly.

All collections require MongoDB JSON Schema validation in deployment migrations. Examples omit ciphertext details for readability.

## `journal_entries`

One document per logical entry with an embedded, bounded revision history. If revision size becomes unbounded, move revisions to a separate collection.

```json
{
  "_id": "ObjectId",
  "entryId": "UUID",
  "userId": "UUID",
  "titleCiphertext": "base64",
  "currentRevision": 2,
  "revisions": [
    {
      "revision": 2,
      "contentCiphertext": "base64",
      "keyVersion": "kek-2026-01",
      "contentHash": "sha256",
      "occurredAt": "ISODate",
      "createdAt": "ISODate"
    }
  ],
  "tags": ["study"],
  "entryDate": "2026-08-11",
  "analysisState": "COMPLETED",
  "deletedAt": null,
  "schemaVersion": 1,
  "createdAt": "ISODate",
  "updatedAt": "ISODate"
}
```

Required indexes:

```javascript
db.journal_entries.createIndex({ entryId: 1 }, { unique: true })
db.journal_entries.createIndex({ userId: 1, entryDate: -1, _id: -1 })
db.journal_entries.createIndex({ userId: 1, updatedAt: -1 })
```

Rules:

- Never index decrypted content or send it to logs/search by default.
- `contentHash` supports revision/job idempotency but must be keyed/HMAC if equality leakage is a concern.
- User authorization uses `userId`; specialist access additionally checks the current PostgreSQL grant and selected `entryId`.
- A new edit creates a revision and invalidates prior "current analysis"; it does not overwrite provenance.

## `journal_analysis_results`

One immutable result per journal revision and analysis run.

```json
{
  "_id": "ObjectId",
  "analysisId": "UUID",
  "jobId": "UUID",
  "entryId": "UUID",
  "userId": "UUID",
  "journalRevision": 2,
  "provider": "OPENAI",
  "model": "model-name",
  "modelVersion": "provider-version",
  "promptVersion": "emotion-v3",
  "schemaVersion": 1,
  "status": "SUCCEEDED",
  "result": {
    "sentiment": "NEGATIVE",
    "sentimentScore": -0.72,
    "emotions": {
      "joy": 0.02,
      "sadness": 0.81,
      "anxiety": 0.64,
      "stress": 0.58,
      "fear": 0.22,
      "anger": 0.08
    },
    "dominantEmotions": ["SADNESS", "ANXIETY"],
    "confidence": 0.79,
    "qualityFlags": []
  },
  "usage": { "inputTokens": 220, "outputTokens": 95 },
  "latencyMs": 834,
  "providerRequestId": "redacted-or-hashed",
  "createdAt": "ISODate"
}
```

Indexes:

```javascript
db.journal_analysis_results.createIndex({ analysisId: 1 }, { unique: true })
db.journal_analysis_results.createIndex({ entryId: 1, journalRevision: -1, createdAt: -1 })
db.journal_analysis_results.createIndex({ userId: 1, createdAt: -1 })
db.journal_analysis_results.createIndex({ jobId: 1 }, { unique: true })
```

Store only the validated structured response needed for product/research provenance. Do not store chain-of-thought. Raw provider responses should be disabled or encrypted with a short TTL if temporarily required for debugging under approved policy.

## `conversations`

```json
{
  "_id": "ObjectId",
  "conversationId": "UUID",
  "appointmentId": "UUID",
  "participants": [
    { "accountId": "UUID", "role": "USER", "joinedAt": "ISODate" },
    { "accountId": "UUID", "role": "SPECIALIST", "joinedAt": "ISODate" }
  ],
  "status": "ACTIVE",
  "lastMessageAt": "ISODate",
  "closedAt": null,
  "schemaVersion": 1,
  "createdAt": "ISODate",
  "updatedAt": "ISODate"
}
```

Indexes:

```javascript
db.conversations.createIndex({ conversationId: 1 }, { unique: true })
db.conversations.createIndex({ appointmentId: 1 }, { unique: true })
db.conversations.createIndex({ "participants.accountId": 1, lastMessageAt: -1 })
```

Participants are a server-generated identity snapshot, not durable authorization. Each consultation conversation is keyed by its confirmed `IN_APP_CHAT` appointment; unrestricted direct specialist conversations are not supported. Realtime Service must check current account/specialist/appointment authorization when opening, subscribing, or sending, and join/send succeeds only inside the appointment's authoritative `[scheduledStartAt, scheduledEndAt)` window. A retention policy may permit read-only history afterward. Future `IN_APP_VIDEO` data is not defined in MongoDB until its separate contract is accepted.

## `messages`

Keep messages separate from conversations to avoid an unbounded document.

```json
{
  "_id": "ObjectId",
  "messageId": "UUID",
  "conversationId": "UUID",
  "senderId": "UUID",
  "clientMessageId": "UUID",
  "type": "TEXT",
  "bodyCiphertext": "base64",
  "keyVersion": "kek-2026-01",
  "sentAt": "ISODate",
  "editedAt": null,
  "deletedAt": null,
  "moderationHold": false,
  "schemaVersion": 1
}
```

Indexes:

```javascript
db.messages.createIndex({ messageId: 1 }, { unique: true })
db.messages.createIndex({ conversationId: 1, sentAt: -1, _id: -1 })
db.messages.createIndex({ senderId: 1, clientMessageId: 1 }, { unique: true })
```

Use cursor pagination. A deletion replaces display content with a tombstone while retention/moderation rules decide encrypted-body removal. Do not put large attachments in MongoDB; store private object keys and validated metadata in a separate `message_attachments` collection or relational metadata.

## `message_receipts`

```json
{
  "_id": "ObjectId",
  "conversationId": "UUID",
  "accountId": "UUID",
  "lastDeliveredMessageId": "UUID",
  "lastDeliveredAt": "ISODate",
  "lastReadMessageId": "UUID",
  "lastReadAt": "ISODate",
  "schemaVersion": 1,
  "updatedAt": "ISODate"
}
```

```javascript
db.message_receipts.createIndex({ conversationId: 1, accountId: 1 }, { unique: true })
```

One high-water mark per participant is preferable to one receipt per message for this two-party chat use case.

## `benchmark_samples` and `benchmark_predictions`

Operational journals must not be reused as benchmark data by default. Imported datasets use de-identified external sample IDs and license/source metadata held in PostgreSQL.

`benchmark_samples` contains `datasetId`, `sampleId`, encrypted/minimized text, expected labels, split, language, preprocessing version, schema version, and timestamps. `benchmark_predictions` contains `runId`, `sampleId`, model/prompt versions, predicted labels/scores, latency, token/cost metadata, error code, and timestamp.

Indexes:

```javascript
db.benchmark_samples.createIndex({ datasetId: 1, sampleId: 1 }, { unique: true })
db.benchmark_samples.createIndex({ datasetId: 1, split: 1 })
db.benchmark_predictions.createIndex({ runId: 1, sampleId: 1, modelKey: 1 }, { unique: true })
```

## Retention and encryption

- Use TLS and encrypted MongoDB volumes/managed encryption.
- Prefer application-level envelope encryption for journal/chat bodies; keep searchable metadata outside ciphertext only when necessary.
- TTL indexes are suitable for explicitly temporary raw-provider/debug documents, not primary journal deletion semantics.
- Deletion workers query by stable `userId`, erase content according to policy, and record completion in PostgreSQL without copying deleted content.
- Backups have a documented expiry and restore process; restored environments must reapply deletion tombstones queued after the backup point.
