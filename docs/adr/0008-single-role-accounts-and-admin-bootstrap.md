# ADR 0008: Single-role accounts and dedicated administrator bootstrap

- Status: Accepted
- Date: 2026-08-26

## Context

The approved actor flows define separate User, Specialist, and Admin actors. They require admin login and bounded administration, but they do not require promoting a registered user or specialist into an administrator or allowing one account to use multiple actor roles. The previous Identity policy, planned role-replacement API, and `account_role` join table introduced that behavior without a supporting use case.

MentalBridge requires one dedicated administrator account for the initial deployment. Its credentials must not be committed to a migration, repository file, image, or log.

## Decision

- Every account has exactly one immutable role: `USER`, `SPECIALIST`, or `ADMIN`.
- Public registration creates only `USER` or `SPECIALIST` accounts.
- A registered user or specialist cannot be promoted to `ADMIN`, and no runtime role-change endpoint is exposed.
- The deployment provisions exactly one dedicated `ADMIN` account through an operator-controlled bootstrap using externally supplied secrets. Database constraints enforce at most one `ADMIN`; deployment readiness requires that the bootstrap has created it.
- Identity persists `role_code` directly on `account`. The `account_role` join table and role-grant metadata are removed.
- JWTs retain the `roles` array claim with exactly one value so existing resource-service authorization remains compatible.

## Consequences

- The logical relationship is `Role 1 - N Account`; there is no associative role entity.
- Account responses may retain a one-element `roles` array for v1 compatibility, but their schemas must set both `minItems` and `maxItems` to one.
- Administrator provisioning and credential rotation are operational security procedures, not public registration or account-administration behavior.
- A future requirement for multiple roles or role promotion requires a new approved use case, contract migration, threat review, ADR, and forward database migration.

## Rejected alternatives

- Many-to-many account roles: rejected because no approved actor flow requires concurrent roles.
- Promoting a user or specialist to administrator: rejected because Admin is a separate, dedicated actor in the current product scope.
- Seeding administrator plaintext credentials in Liquibase: rejected because migrations and repository history are not secret-delivery mechanisms.
