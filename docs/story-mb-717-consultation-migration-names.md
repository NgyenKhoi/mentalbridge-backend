# MB-717 Consultation migration naming

## Brainstorm and selected correction

Owner: Thảo Nguyên. Base: backend `ff0f924f` after MB-693 #219. Frontend dev was
also synchronized to `af26301`; this task changes only backend test/documentation
paths. The task is migration naming and compatibility, not a domain schema change.

The two `021` prefixes are ambiguous to people, but they are **not duplicate
Liquibase identities**. Liquibase identifies a changeset by `id`, `author` and
filepath; checksum validation separately protects its contents. The master uses
explicit includes, with plan-action reference before sandbox commercial policy.

Considered options:

1. Renumber an existing file or changeset. Rejected: the existing filepath/ID
   participates in applied identity and can make Liquibase attempt the DDL/seed
   again. `75108709` renamed the earlier payout `016` to `017`; the configured
   database now has the `017` identity. That historical rename is not evidence
   that renaming today's applied changesets is safe.
2. Rename a physical file with an explicit legacy `logicalFilePath`. This can
   preserve identity if correctly configured, but adds compatibility metadata to
   applied migrations solely to change a numeric prefix. There is no runtime
   collision to justify that risk here.
3. **Keep the applied history unchanged**, document the two `021` legacy entries
   by full identity and order, and enforce timestamp filenames for new files.
   Selected after reading actual applied metadata. This removes the operational
   ambiguity without changing shared database state or deploying migration DDL.

Liquibase's [changeset identity documentation](https://docs.liquibase.com/oss/user-guide-4-33/what-is-a-changeset)
and [filepath guidance](https://support.liquibase.com/hc/en-us/articles/29383060554523-Can-I-move-a-file-or-restructure-my-Liquibase-project)
support the identity distinction. The executable checks use this repository's
Liquibase **5.0.3**, rather than assuming another release's behavior.

## Applied history inspection

On 2026-10-22, a standalone PostgreSQL JDBC query inspected only the configured
Consultation database's `public.databasechangelog` metadata. Connection default
and transaction were forced read-only, `SHOW transaction_read_only` was checked,
statement/connect/socket timeouts were bounded, and the transaction was rolled
back. No Spring application or Liquibase command ran against that database.
No business tables, credentials, hosts or database URLs are recorded here.

Exact reads inside that read-only JDBC transaction (then `rollback()`):

```sql
show transaction_read_only;
select id, author, filename, md5sum, orderexecuted, exectype, liquibase
from public.databasechangelog order by orderexecuted;
```

There were **29 rows**, all `EXECUTED`, Liquibase `5.0.3`. Relevant rows, all by
author `mentalbridge`, in actual `ORDEREXECUTED` order:

| Order | ID | Filepath under `db/changelog/changes/` | MD5SUM |
| --- | --- | --- | --- |
| 17 | `consultation-017-specialist-earning-payout` | `017-specialist-earning-payout.sql` | `9:62c63fbbc90fafd8903b207044529785` |
| 21 | `consultation-021-appointment-plan-action-reference` | `021-appointment-plan-action-reference.sql` | `9:c29e4cb92a3e450c04b6620e39f435b2` |
| 22 | `consultation-021-sandbox-commercial-policy` | `021-sandbox-commercial-policy.sql` | `9:7b28b995a3312d01616ad3dc860bf374` |

No explicit logical filepath override exists in these files; the parser-resolved
filepath is the include path shown above. The test-only
[baseline fixture](../consultation-service/src/test/resources/db/changelog/consultation-legacy-v1.tsv)
records all 29 ordered identities and version-9 checksums from that read. It has
no data-plane identifiers or personal data. Other deployments were not queried;
the metadata is evidence for the configured shared pre-production database only.

## Ordered implementation slices

1. Freeze the inspected identity/checksum/order baseline in parser tests. Permit
   only appended changesets, require every SQL file to be included, reject new
   numeric filenames, invalid calendar timestamps, uppercase descriptions and
   duplicate new timestamp prefixes. Document the naming rule and the legacy
   exception together. Focused check: `ConsultationLiquibaseChangelogTests`.
2. Extend existing PostgreSQL migration integration tests: compare the freshly
   applied legacy prefix to the inspected metadata, validate the changelog,
   assert no unrun changesets, run update again and compare every history column
   before/after. Check: `ConsultationLiquibaseMigrationTests`; then the complete
   Consultation `clean verify` module gate.
3. Review the base-to-head diff, repository policy, links, overlap and CI. Keep the
   delivery scoped; no synthetic production migration or unrelated schema edit.

## Acceptance states

| State | Expected result | Forbidden outcome | Evidence |
| --- | --- | --- | --- |
| Configured applied history | Two distinct `021` identities at orders 21/22 | Rename, checksum reset, history rewrite or DDL replay | Read-only metadata above |
| Empty disposable database | Current master applies all legacy changes in original order | New identity for old SQL or missing schema | Existing migration integration suite plus baseline comparison |
| Already migrated disposable database | Validation succeeds, no pending changes; update leaves every history column unchanged | Old DDL/seed executed again or EXECUTED replaced by RERAN | New replay regression |
| Future migration | Valid UTC timestamp filename, unique new prefix, explicit appended include | New `029`/`021`, invalid date/hour, duplicate timestamp, orphan SQL or insertion into legacy prefix | Parser/naming regressions |
| Older deployed application | Same historical files, IDs, checksums and master order | Need for coordinated data rewrite or backfill | Runtime changelog directory unchanged in diff |

## Compatibility and rollout

No migration file, master include, persisted schema, canonical logical model,
field dictionary, REST/event contract, dependency or runtime setting changes.
The existing `021` physical filenames intentionally remain as immutable legacy
names. New files use UTC `yyyyMMddHHmmss-description.sql`; execution order remains
the explicit master include order. Do not regenerate the baseline to admit a new
migration, change old checksums or use `clearCheckSums`, `changelogSync` or
`DATABASECHANGELOG` writes as part of this task.

Only test-owned PostgreSQL is migrated by verification. No shared migration,
seed, reset, Compose change, Docker stack restart or deployment is performed.
Rollback is reverting test/documentation changes; there is no data rollback.

## Execution

Parser/checksum/naming check: `./mvnw.cmd -B -q
-Dtest=ConsultationLiquibaseChangelogTests test`, Windows, Java 21, Liquibase
5.0.3: **9 passed, zero failures/errors/skips**. The first local
Maven wrapper attempt failed before Maven started because the sandbox account's
Maven home lookup returned a null symlink target; the same checked-in wrapper
under the normal user environment is used for the actual checks. No wrapper or
test assertions were weakened.

PostgreSQL migration check: `./mvnw.cmd -B -q
-Dtest=ConsultationLiquibaseMigrationTests test`, Java 21, Testcontainers
PostgreSQL **18.6** (`postgres:latest`, the existing repository fixture): **7
passed, zero failures/errors/skips**. Validation and pending-change inspection
passed; the second update left every `DATABASECHANGELOG` column unchanged.
The first attempt had one test cleanup error after the Liquibase assertions
because both the database wrapper and Liquibase owned connection cleanup. Giving
Liquibase sole ownership fixed it; the complete seven-test rerun passed.

The full Consultation `clean verify` module gate is running; its result is not
claimed yet. Unchanged services remain in repository CI; no local repository-wide
suite is needed for these Consultation-only test/documentation changes.
