# PostgreSQL Conventions

## Ownership and consistency

Each service owns a separate database and database user, even when services share one PostgreSQL server locally. Service tables use the owned database's default `public` schema. Owner migrations are executable runtime truth. The read-only [`postgresql-logical-schema.sql`](../domain-model/relational/postgresql-logical-schema.sql) groups tables into visual owner namespaces for documentation only; its cross-owner identifiers are logical/external relationships, never physical foreign keys. No runtime query or ORM relationship may cross a service boundary.

Before adding or changing persisted data, inspect both the owner migration
history and the canonical model. A migration PR that adds, removes, renames, or
materially changes domain persistence is incomplete until the canonical model
and the field data dictionary are updated. Non-unique performance-index tuning,
migration metadata, and constraint name-only changes do not require a logical
model update.

Use database constraints as the final guard for local invariants: `NOT NULL`, `CHECK`, `UNIQUE`, foreign keys within one owner, exclusion constraints, and appropriate locking. Application validation improves errors but does not replace integrity constraints.

Use optimistic locking for concurrent editable aggregates. Use a unique/exclusion constraint or deliberate row lock for contention such as slot booking. Convert integrity violations into stable domain conflicts instead of checking then inserting without protection.

## Mandatory descriptions

Every new or changed table and column must have a plain-language description that answers:

- what business fact it stores;
- why the fact must be persisted;
- units, timezone, format, or allowed-value meaning where applicable;
- whether it is authoritative, derived, encrypted, hashed, external, nullable, or retained for audit;
- which service owns the value and which operation changes it when that is not obvious.

Descriptions belong in `docs/database/postgresql-field-data-dictionary.md`, with aggregate-level reasoning and access patterns added to the owning module README when useful. Do not add descriptions as SQL comments merely to duplicate this documentation.

Example:

```text
consultation.appointment
- idempotency_key: Caller-generated retry key, unique per user, so an uncertain REST retry returns the original booking outcome.
- user_timezone: IANA timezone captured at booking so schedules remain stable after a device timezone change.
```

Do not use descriptions that merely repeat the name, such as “appointment ID.” Explain purpose: “Immutable external reference used in appointment events and REST resources.”

## Standard field semantics

| Field | Required description semantics |
| --- | --- |
| `id` | immutable opaque identifier and where it is exposed |
| external `*_id` | identifier owned by another service; not proof the object still exists or is authorized |
| `created_at` | immutable insertion instant in UTC |
| `updated_at` | last persisted business change in UTC and who updates it |
| `version` | optimistic-lock value, incremented on mutation |
| `status` | workflow state, transition owner, and terminal states |
| `*_at` | exact event represented, UTC, and why nullable |
| `*_version` | policy/schema/content version needed for reproducibility |
| `*_hash` | one-way purpose and explicitly not recoverable plaintext |
| `*_ciphertext` | encrypted content plus key/rotation relationship |
| `idempotency_key` | scope, uniqueness, retention, and replay behavior |
| `correlation_id` | trace/workflow grouping, not a business key |
| `reason_code` | stable machine-readable explanation, not unrestricted sensitive text |

## Migration and query review

- Migrations are append-only after merge and owned per service. Use expand/migrate/contract for rolling compatibility.
- Never execute the canonical logical schema or wire it into provisioning, migration, startup, Compose, tests, or deployment tooling.
- Store instants as `timestamptz`; keep an IANA timezone separately for human schedules. UUIDs cross REST/message boundaries as strings.
- Index from real access paths. For every index, identify the query, filter/order, cardinality, and write/storage tradeoff.
- Pagination is deterministic and bounded. Use cursor/keyset pagination for growing histories and include a unique tie-breaker.
- Never expose persistence entities directly from REST or messages.
- Repository integration tests run against real PostgreSQL and verify migration application, constraints, locking, and query mapping. Review checks verify that the Markdown data dictionary covers all application-owned tables/fields.
