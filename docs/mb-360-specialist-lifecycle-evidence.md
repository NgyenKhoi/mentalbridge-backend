# MB-360 specialist lifecycle evidence

## Scope

MB-360 implements specialist rejection, same-profile resubmission, suspension,
and restoration in Consultation Service. It uses the Story 6102 acceptance
criteria imported in `MentalBridge_Sprint3_Post_Screening_Horizontal_Feature_Expansion_Import.csv`.

## Contract and owner behavior

- Specialists can read their current reason, edit a rejected profile, and
  explicitly resubmit it with `If-Match`.
- Administrators can list by lifecycle status and approve, reject, suspend, or
  restore with optimistic concurrency.
- Rejection and suspension accept closed, purpose-specific reason enums.
- Every successful state transition appends an actor-attributed audit record.
- Replays and invalid state transitions return bounded conflicts rather than
  duplicating effects.

## Suspension invariant

One database transaction locks the specialist profile, marks it suspended,
withdraws future active availability, cancels future `REQUESTED`/`CONFIRMED`
appointments, releases each exact `HELD` credit, and appends credit and
appointment histories. A missing or mismatched credit aborts the transaction.
Restoration changes only the profile state.

## Verification

The focused suite covers contract operations and enums, append-only Liquibase
migration and database constraints, valid and invalid transitions, reason
visibility, same-profile resubmission, authorization, suspension side effects,
idempotent replay, and non-restoration of withdrawn/cancelled records.

On 2026-09-24 the following owner checks passed locally with synthetic data and
PostgreSQL Testcontainers (`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`):

```text
mvn -Dtest=SpecialistProfileFlowIntegrationTests,ConsultationLiquibaseMigrationTests,ConsultationLiquibaseChangelogTests,ConsultationOpenApiContractTests test
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0

mvn test
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

powershell -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev
Repository policy passed; paired-change policy passed.
```

The service-integration assertions verify this redacted suspension outcome:

```json
{
  "profile": {
    "approvalStatus": "SUSPENDED",
    "decisionReasonCode": "QUALITY_REVIEW_REQUIRED"
  },
  "effects": {
    "withdrawnAvailabilitySlots": 1,
    "cancelledAppointments": 1,
    "releasedCredits": 1
  }
}
```

This is service-integration evidence, not a live cross-stack claim. Public
discovery and booking remain their separately owned runtime stories. MB-360
adds only the narrow appointment persistence needed to make suspension safe for
already-persisted future appointments. Notification behavior is the
authenticated reason/outcome returned on reload; no independent asynchronous
notification consumer exists in this slice, so Kafka/outbox work is
intentionally deferred.
