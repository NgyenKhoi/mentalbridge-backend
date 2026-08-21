# Identity Service specification

## Business boundary

Identity owns accounts, credentials, roles, email ownership, refresh sessions, account state, deletion coordination, and a minimized security/audit projection. It does not own health profiles, consent, specialist qualifications, journals, or appointments. PostgreSQL is authoritative; Redis may hold expiring hashed OTP challenges and bounded rate/security state only.

## Use cases and acceptance

| Capability | Main behavior | Acceptance |
| --- | --- | --- |
| Registration/verification | Register USER or SPECIALIST and verify email; Consultation separately records a specialist profile approval as pending without document upload | Duplicate normalized email conflicts; passwords are hashed; OTP/token expires, is one-use and rate-limited; account/outbox commit together |
| Login/session | Authenticate active account; issue short access token and rotated refresh session | Generic credential errors; disabled/unverified policy enforced; refresh replay revokes the affected chain; logout is idempotent |
| Password recovery/change | Verify ownership and replace credentials | Expired/reused challenge fails; existing sessions follow reviewed revocation policy; secrets never enter logs/events |
| Account/RBAC admin | Query and change account state/roles through bounded admin APIs | Admin authorization at owner; last privileged-role and transition rules explicit; stable audit fact emitted |
| Deletion coordination | Start and track an idempotent fan-out workflow | Repeated request returns same workflow; every owner task tracked; retained audit is minimized/pseudonymized |
| Audit/search projection | Search permitted security/operational facts | No health/free-text content; bounded filters/pagination; retention enforced by each owner |

## Implementation design

- Account lifecycle, role, credential, session, and recovery decisions are defined in [Identity account and session policy](identity-security-policy.md).
- Feature slices: `registration`, `authentication`, `sessions`, `recovery`, `account-admin`, `deletion`, `audit-projection`.
- Define Identity OpenAPI first, including RFC 9457 errors and idempotency. Publish versioned account/deletion/audit schemas.
- Split identity/platform baseline tables into owner Liquibase changelogs and update the field dictionary. Use constraints for unique email and token/session invariants.
- Keep external providers such as Brevo behind narrow ports. Keep JWT signing, hashing, persistence locking, and idempotency in focused owner components without one-to-one wrapper interfaces. Identity has no outbound business REST dependency.
- Test cryptographic boundaries with deterministic fixtures, not production secrets; use real PostgreSQL/Kafka/Redis containers for integration behavior.

## Ordered tasks

- [x] ID-01 Define account lifecycle, role matrix, token/session and recovery policies; resolve session revocation ambiguity.
- [x] ID-02 Publish registration, verification, login, refresh, logout, recovery and account-admin OpenAPI.
- [ ] ID-03 Publish account lifecycle, deletion and minimized audit event schemas.
- [x] ID-04 Add owner Liquibase migrations, data dictionary entries, constraints and query indexes.
- [x] ID-05 Implement registration/verification and safe delivery request integration.
- [x] ID-06 Implement login, refresh rotation/replay detection and logout.
- [ ] ID-07 Implement password recovery/change and account-state/RBAC administration.
- [ ] ID-08 Implement deletion coordinator, idempotent task projection and retained-audit minimization.
- [ ] ID-09 Verify validation, authorization, rate limit, concurrency, replay, outbox rollback, consumer duplicates and dependency failures.
- [ ] ID-10 Add metrics/readiness/configuration, update module README, and pass module/contract/migration build gates.
