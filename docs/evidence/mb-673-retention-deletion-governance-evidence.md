# MB-673: [ADMIN] Govern Data Retention and Deletion Execution with Audit-Safe Evidence

## Executive Summary
This document provides audit-safe evidence and operational boundary documentation for task **MB-673**: *Govern data retention and deletion execution with audit-safe evidence*.

Under the MB-583 architecture, `Identity Service` coordinates deletion workflows and owns retention for accounts and authentication audit records. Each domain service (`Care`, `Consultation`, `Journal AI`, `Community`, `Content`) maintains autonomous authority over its domain data lifecycle. No universal hard-delete button is implemented, and no cross-service direct database access is permitted. All user deletion facts are traceable using privacy-minimized tombstone hashes (`tombstone:<sha256>`), with bounded retries, fail-closed hold enforcement, and explicit unavailability for unsupported capabilities.

---

## 1. Owner and Policy Authority Matrix

| Policy ID | Policy Name | Owner Service | Version | Retention Period | Sweep Capability | Manual Hold Supported | Safe Aggregate Outcome |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `MB-IDENTITY-REPORTING-001` | Chính sách bảo lưu tệp báo cáo tổng hợp | `IDENTITY` | 1.0 | 30 ngày | **ACTIVE** (Automated & Admin sweep supported via authoritative metrics) | Không áp dụng | Số lượng tệp trích xuất báo cáo phân tích hết hạn đã dọn dẹp |
| `MB-IDENTITY-AUDIT-001` | Chính sách bảo lưu nhật ký kiểm toán Identity | `IDENTITY` | 1.0 | 365 ngày | **PARTIALLY_SUPPORTED** (Bảo lưu bất biến MB-587; không hỗ trợ xóa sớm/thủ công) | Không áp dụng | Nhật ký kiểm toán danh tính tối thiểu hóa |
| `MB-PRIVACY-CARE-001` | Chính sách bảo lưu sàng lọc & đồng ý chăm sóc | `CARE` | 1.1-capstone | Quản lý theo vòng đời Care (TTL 30p-2h ẩn danh, có tài khoản bảo lưu kiểm soát) | **UNAVAILABLE** (`NO_AUTHORITATIVE_SIGNAL`; Care domain-managed only) | **Không có owner contract** | Bản ghi đánh giá tâm lý lâm sàng và hồ sơ phiên chăm sóc |
| `consultation-journey-v1` | Chính sách bảo lưu phiên tư vấn & đối soát giao dịch | `CONSULTATION` | 1.0-scope-v2 | 90 ngày (LiveKit metadata, tài chính kế toán theo luật) | **UNAVAILABLE** (`NO_AUTHORITATIVE_SIGNAL`) | **Không có owner contract** | Lịch sử cuộc hẹn và ghi chú tư vấn hoàn tất |
| `MB-DAILY-EMOTION-CHECK-IN-001` | Chính sách bảo lưu & xóa nhật ký cảm xúc | `JOURNAL_AI` | 1.0 | Quản lý mã hóa Journal AI (xóa ciphertext, tombstone có giới hạn) | **UNAVAILABLE** (`NO_AUTHORITATIVE_SIGNAL`) | **Không có owner contract** | Nhật ký cảm xúc định kỳ và vector suy luận AI |
| `MB-COMMUNITY-RETENTION-001` | Chính sách bảo lưu nội dung cộng đồng | `COMMUNITY` | 1.0 | 7 ngày (phương tiện chưa liên kết) | **UNAVAILABLE** (`NO_AUTHORITATIVE_SIGNAL`) | Không áp dụng | Tệp đính kèm tạm thời bài đăng cộng đồng |
| `MB-CONTENT-NOTIFICATION-RETENTION-001` | Chính sách bảo lưu hộp thư thông báo | `CONTENT` | 1.0 | 90 ngày | **UNAVAILABLE** (Explicitly Unavailable) | Không áp dụng | Hộp thư thông báo nội dung không quan trọng |

### Authority & Architectural Rules
1. **Coordination vs Enforcement**: Identity coordinates deletion requests and orchestrates asynchronous owner facts. Each owner service processes its own deletion facts and emits audit evidence.
2. **No Fabricated Facts (P0-A)**: Identity only confirms completion of its own internal fact (`IDENTITY`). External facts (`CARE`, `CONSULTATION`, `JOURNAL_AI`, `COMMUNITY`, `CONTENT`) remain `PENDING` or `UNAVAILABLE` until authoritative cross-service contracts or events report completion. Identity never fabricates fake evidence references or marks external facts completed. Empty owner facts collection strictly rejects completion (`!request.ownerFacts().isEmpty()` and identity fact presence are enforced). Missing accounts mark Identity fact as `FAILED` (`ACCOUNT_NOT_FOUND`) and do not infer account as `DELETED`.
3. **Authoritative Sweep Metrics (P0-B)**: Retention sweeps query authoritative database metrics directly (via `PlatformReportProcessor.purgeExpiredArtifactsWithMetrics()`), eliminating all fabricated or hardcoded offsets.
4. **Failure Execution Persistence (P0)**: Failure execution records are persisted in isolated transactions (`REQUIRES_NEW`) via `RetentionExecutionRecorder.recordFailure`, guaranteeing audit evidence commit even when business exceptions roll back HTTP transactions, with safe non-leaking generic error responses.
5. **Safe Deletion Retry & Attempt Gating (P0-C / P1)**: Retry validates lifecycle state, blocks requests scheduled in the future (`scheduledExecutionAt.isAfter(now)`), active holds, or exhausted retry limits. Retries are strictly bounded (`maxRetries = 3`). Completed requests or successfully retried final attempts clear escalation state to `NONE`. Retries where Identity has already completed fail closed (HTTP 422 `EXTERNAL_OWNER_RETRY_UNSUPPORTED`) without consuming bounded retry attempts.
6. **Fail-Closed Capabilities (AC 5)**: Capabilities not supported by owner services (e.g. direct sweep invocation on Care records or Content notifications) are explicitly declared `UNAVAILABLE` / `PARTIALLY_SUPPORTED` and fail closed (HTTP 422 `CAPABILITY_UNSUPPORTED`) rather than simulating success.
7. **Strict Privacy Bounds (AC 2)**: Under no circumstances are raw Journal texts, assessment responses, chat transcripts, audio/video streams, or system credentials returned or displayed in retention views.
8. **Safe Hold Reason Codes (P1-D)**: Hold reasons are strictly restricted to a whitelist of safe reason codes (`STATUTORY_INQUIRY`, `PENDING_DISPUTE`, `ACTIVE_SAFETY_REVIEW`, `SCHEDULED_APPOINTMENT_ACTIVE`, `USER_CONTESTED`, `AUDIT_COMPLIANCE_HOLD`) preventing free-text PII leakage. Hold cannot be applied to `COMPLETED` requests. Applying or releasing holds strictly targets coordinator deletion request state and does NOT mutate or falsify external owner fact states.
9. **Audit Transaction Isolation (P1-E)**: Audit logging enforces exact transactional boundaries: `RetentionAuditLogger.recordDenied` uses `REQUIRES_NEW` (ensuring denied audit records persist even when the caller transaction rolls back), while `recordSuccess` uses `REQUIRED` (ensuring success audit records roll back if the business transaction fails).
10. **Deletion Evidence Preservation (P1-I)**: Foreign key `account_id` on `deletion_request` does NOT cascade delete on account removal, ensuring immutable audit evidence and traceability are permanently preserved.
11. **Pagination Integration (P1)**: Authoritative pagination via `page` and `nextCursor` integrated seamlessly across OpenAPI, BFF, identity-client, and frontend UI pagination controls.
12. **Job Status Provenance (P2)**: Every policy reports `jobStatusSource`. Identity-owned state comes from `IDENTITY_POLICY_CATALOG` or `IDENTITY_EXECUTION_RECORD`; the latest failed execution is reported as `FAILED`, while the separate `manualEscalationRequired` flag becomes true only after the existing three-consecutive-failure threshold. External owners without an approved live status contract report `UNAVAILABLE` with `NO_AUTHORITATIVE_SIGNAL` instead of presenting a fabricated `ACTIVE` state.
13. **Evidence-to-Audit Linkage (P1)**: Identity completion evidence uses `audit:identity:account-deleted:<audit-event-uuid>`, where the UUID is the committed success audit event ID, not the deletion request ID. The owner-fact update and success audit use the same transaction, so neither can independently claim completion after rollback.
14. **Coordinator vs Owner Holds (P2)**: ADMIN hold endpoints manage the Identity coordinator deletion request only. `manualHoldSupported` represents an authoritative owner-level hold contract; external owners report false until such a contract exists.

---

## 2. Technical Implementation Details

### 2.1 Backend (`identity-service`)
- **Database Migration**: Liquibase changeset `006-retention-and-deletion-governance.sql`:
  - `deletion_request`: Coordinates deletion requests, bounded retries (`retry_count`, `max_retries = 3`), escalation states (`NONE`, `RETRY_EXHAUSTED`, `MANUAL_ESCALATION_REQUIRED`), hold state (`hold_type`, `hold_reason`), and account status. `account_id` preserves evidence without cascading delete.
  - `deletion_owner_fact`: Records per-service completion facts, evidence references, and failure reasons across `IDENTITY`, `CARE`, `CONSULTATION`, `JOURNAL_AI`, `COMMUNITY`, `CONTENT`.
  - `retention_execution_record`: Records sweep outcomes (`items_evaluated`, `records_purged`, `tombstones_created`, `holds_honored`).
- **Domain Service (`AdminRetentionGovernanceService`)**:
  - Implements approved policy catalog and execution dispatch.
  - Enforces fail-closed rules: blocks deletion execution if active hold (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) is present.
  - Limits retry attempts to bounded threshold (`max_retries = 3`), transitioning exhausted requests to `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
  - Generates minimized tombstone references (`tombstone:sha256Hex`).
  - Records isolated audit trail for policy sweeps, hold applications, hold releases, and deletion retries via `RetentionAuditLogger`.
- **REST Controller (`AdminRetentionGovernanceController`)**:
  - Secured with `@PreAuthorize("hasRole('ADMIN')")`.
  - Endpoints:
    - `GET /api/v1/admin/retention/policies`
    - `POST /api/v1/admin/retention/policies/{policyId}/sweep`
    - `GET /api/v1/admin/retention/deletions`
    - `POST /api/v1/admin/retention/deletions/{requestId}/retry`
    - `POST /api/v1/admin/retention/deletions/{requestId}/holds`
    - `DELETE /api/v1/admin/retention/deletions/{requestId}/holds`
- **OpenAPI & Contracts**: Fully specified in `contracts/openapi/identity-service-v1.yaml`.

### 2.2 Frontend BFF & UI (`mentalbridge`)
- **Type Generation & Validation**:
  - Auto-generated TypeScript types via openapi-typescript into `contracts/identity.generated.ts`.
  - Re-exported in `features/auth/api/identity-contract.ts`.
  - Defensive runtime parsers in `lib/auth/identity-validation.ts`.
- **BFF Client & Endpoints**:
  - Handlers:
    - `app/api/admin/retention/policies/route.ts`
    - `app/api/admin/retention/policies/[policyId]/sweep/route.ts`
    - `app/api/admin/retention/deletions/route.ts`
    - `app/api/admin/retention/deletions/[requestId]/retry/route.ts`
    - `app/api/admin/retention/deletions/[requestId]/holds/route.ts` (POST and DELETE)
- **UI Components**:
  - `AdminRetentionGovernance.tsx`:
    - Policy tab: Displays policy version, owner, status, last execution, safe aggregate counts, and explicitly unavailable indicators with disabled sweep buttons.
    - Destructive Identity sweeps require an accessible two-step confirmation naming the exact policy, owner, retention scope, and irreversible effect; cancellation sends no request and in-flight submission is single-shot.
    - Deletions tab: Minimized identifiers (`tombstone:<hash>`), status/hold badges, retry counter (`retryCount / maxRetries`), expandable owner facts table with evidence references, and hold management modal with safe reason selector.
    - Deletion list reads and mutation refreshes share one request epoch and the latest filter/page snapshot, so stale responses cannot restore outdated actionable rows.
    - Audit-Safe Guarantee Banner: Explicit notice affirming privacy guarantees and absence of raw journal, assessment, or chat text.
  - Integrated into `AdminWorkspace.tsx` under section `/admin/retention` ("Bảo lưu & xóa dữ liệu").

---

## 3. Test & Verification Results

### 3.0 Latest scoped hardening verification (2026-10-25)

| Check | Result | Actual tests |
| :--- | :--- | :--- |
| Backend service/controller/OpenAPI/transaction integration selection | **PASS / SKIPPED** | Maven selected **56 tests: 50 passed, 0 failed, 0 errors, 6 skipped**. `AdminRetentionGovernanceServiceTests` 38/38, `AdminRetentionGovernanceControllerTests` 11/11, and `IdentityOpenApiContractTests` 1/1 passed. All 6 `RetentionTransactionIntegrationTests`, including the new committed evidence-to-audit linkage assertion, were **SKIPPED** because Docker was unavailable. |
| Frontend retention + identity validation unit tests | **PASS** | **34 passed, 0 failed** (21 retention component + 13 identity validation). |
| Frontend contract consistency | **PASS** | `npm.cmd run contracts:check` completed successfully for all configured contracts. |
| Frontend typecheck | **PASS** | `npm.cmd run typecheck` completed with 0 errors. |
| Changed frontend files lint | **PASS** | Targeted ESLint completed with 0 errors and 0 warnings. |
| Changed human-authored frontend files format | **PASS** | Targeted Prettier check completed successfully. Generated Identity artifacts remain generator-owned and were validated by `contracts:check`. |
| Live cross-service deletion E2E | **NOT RUN** | No deployed authoritative external-owner workflow was available; no result is fabricated. |

### 3.0a Previous local verification (2026-10-24; historical, not rerun on current worktree)

| Check | Result | Actual tests |
| :--- | :--- | :--- |
| `AdminRetentionGovernanceServiceTests` | **PASS** | **37 run, 0 failed, 0 errors, 0 skipped**; validates P0 retry consistency when account is missing (`FAILED` status, `ACCOUNT_NOT_FOUND`, audit DENIED), retry exhaustion escalation, hold lifecycle, GET/POST agreement for orphaned runs, fail-closed privacy boundary sanitization against raw email, bare JWT, API keys, malformed UUIDs, disguised secrets under `audit:*`, unapproved external owner evidence, and owner fact failure reason strictly restricted to authoritative `ACCOUNT_NOT_FOUND` failing all uncontracted/external reasons closed to null. |
| `AdminRetentionGovernanceControllerTests` | **PASS** | **11 run, 0 failed, 0 errors, 0 skipped**; validates HTTP endpoints, correlation headers, and parameter validation. |
| `AdminRetentionGovernanceSecuritySliceTests` | **PASS** | **7 run, 0 failed, 0 errors, 0 skipped**; validates RBAC `@PreAuthorize("hasRole('ADMIN')")` across all GET, POST, and DELETE mutating endpoints. |
| `RetentionAuditLoggerTransactionTests` | **PASS** | **3 run, 0 failed, 0 errors, 0 skipped**; validates `REQUIRES_NEW` audit persistence on rollback. |
| `RetentionExecutionRecorderTransactionTests` | **PASS** | **3 run, 0 failed, 0 errors, 0 skipped**; validates failure record commit and success rollback. |
| `IdentityOpenApiContractTests` | **PASS** | **1 run, 0 failed, 0 errors, 0 skipped**; validates OpenAPI schema endpoints, status codes, and `evidenceReference` pattern/maxLength constraints. |
| `RetentionTransactionIntegrationTests` | **NOT RUN (LATEST)** | Docker/Testcontainers was unavailable in this environment. Historical 5/5 pass is retained below and is not presented as a latest result. |
| Total targeted backend run | **PASS** | **62 run, 0 failed, 0 errors, 0 skipped** (37 service + 11 controller + 7 security + 1 OpenAPI + 3 execution transaction + 3 audit transaction). |
| Full `identity-service` suite | **NOT RUN** | Focused MB-673 suites and repository policy were run; no full backend suite result is claimed for this worktree. |
| `AdminRetentionGovernance.test.tsx` + `identity-validation.test.ts` | **PASS** | **33 run, 0 failed** (20 retention + 13 validation), including sweep confirm/cancel/failure/duplicate guards, external-owner status provenance, stale response fencing, and mutation/filter races. |
| Full frontend unit suite (`npm run test:unit`) | **PASS** | **297 files, 2126 tests passed, 0 failed**. |
| Frontend code formatting (`npm run format:check`) | **PASS** | Prettier code style verified across all matched files with 0 warnings. |
| Frontend contract consistency (`npm run contracts:check`) | **PASS** | All 6 microservice OpenAPI snapshots and generated TypeScript interfaces valid. |
| Frontend typecheck (`npm run typecheck`) | **PASS** | Next.js typegen and `tsc --noEmit` clean with 0 errors. |
| Frontend lint (`npm run lint`) | **PASS** | `eslint --max-warnings=0` clean with 0 warnings. |
| Frontend realtime contract checks | **PASS** | `contract:check` and `contract:provider-check` passed; provider source was explicitly set to the current backend workspace, matching CI checkout semantics. |
| Frontend production build (`npm run build`) | **PASS** | Next.js production build completed and generated 143 static pages. |
| Playwright Synthetic E2E (`admin-retention-governance.spec.ts`) | **NOT RUN (LATEST)** | Local runner reached healthy fixture servers but did not start a browser test under the current application-control environment; it was terminated without a fabricated result. Historical pass remains in section 3.4. |
| Backend repository quality gate (`scripts/verify-repository.ps1 -BaseSha origin/dev`) | **PASS** | Repository policy passed (1863 tracked files checked; paired-change policy passed for 46 changed files). |
| Documentation policy tests | **PASS** | **20 tests passed**. |
| Live cross-service E2E (`npm run test:e2e:live`) | **NOT RUN** | Requires deployed multi-service live environment. |

During historical database testing, two early runs failed before executing test bodies because the `deletion_request.subject_reference_hash` JPA mapping expected JDBC `VARCHAR` while the existing migration creates `char(64)`; setting only `columnDefinition` did not change the JDBC type. The mapping was updated to use the repository's existing `@JdbcTypeCode(SqlTypes.CHAR)` pattern, and that earlier snapshot's five-test database integration suite passed completely (5/5 PASS) with active Testcontainers PostgreSQL. This is not a current-worktree result. No migration or transaction propagation was changed.

Sections 3.1–3.4 below record verification from earlier branch snapshots; their counts are **HISTORICAL PASS**, not tests rerun for this worktree.

### 3.1 Historical Backend Test Results (`mvnw.cmd test`)
Executed test suites:
- `AdminRetentionGovernanceServiceTests` (27 tests)
- `AdminRetentionGovernanceControllerTests` (11 tests)
- `AdminRetentionGovernanceSecuritySliceTests` (5 tests)
- `RetentionAuditLoggerTransactionTests` (3 tests)
- `RetentionExecutionRecorderTransactionTests` (3 tests)
- `IdentityOpenApiContractTests` (1 test)

```text
[INFO] Running com.mentalbridge.identity.contract.IdentityOpenApiContractTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.350 s -- in com.mentalbridge.identity.contract.IdentityOpenApiContractTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.875 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceSecuritySliceTests
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.625 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceSecuritySliceTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.620 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] Running com.mentalbridge.identity.retention.RetentionAuditLoggerTransactionTests
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.130 s -- in com.mentalbridge.identity.retention.RetentionAuditLoggerTransactionTests
[INFO] Running com.mentalbridge.identity.retention.RetentionExecutionRecorderTransactionTests
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in com.mentalbridge.identity.retention.RetentionExecutionRecorderTransactionTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Key verified test assertions:
- Service returns all 7 approved policies with authoritative metadata matching documented repository contracts.
- Fail-closed on advisory lock exception: `triggerRetentionSweep` rejects with HTTP 409 Conflict (`LOCK_ACQUISITION_FAILED`) without purging data if lock cannot be acquired or throws an exception.
- `getRetentionPolicies` does not falsely infer `STALE` status if advisory lock lookup throws an exception.
- Full RUNNING -> SUCCEEDED / FAILED / STALE lifecycle: `recordStart` commits `RUNNING` state before sweep execution; `recordSuccess` updates the same record to `SUCCEEDED` within transaction; `recordFailure` updates it to `FAILED` with `REQUIRES_NEW` on error.
- Hold lifecycle preservation: `applyHold` and `releaseHold` strictly preserve prior `FAILED` or `PENDING` states, never turning `FAILED` into `IN_PROGRESS` based solely on `retryCount`.
- Triggering sweep for unsupported capability (`MB-PRIVACY-CARE-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`) throws `UnsupportedRetentionCapabilityException` (HTTP 422) and logs DENIED audit.
- Authoritative reporting sweep metrics evaluated directly from database (`PlatformReportProcessor.purgeExpiredArtifactsWithMetrics()`).
- Applying `LEGAL_HOLD` / `OPERATIONAL_HOLD` blocks retries with `DeletionHoldActiveException` (HTTP 409) and logs DENIED audit.
- Bounded retry increments count and throws `DeletionRetryExhaustedException` at max retries (3), transitioning state to `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
- Non-fabrication: Identity only marks its own fact completed; CARE/CONSULTATION/JOURNAL_AI/COMMUNITY/CONTENT facts remain PENDING. Overall request remains IN_PROGRESS until all facts complete.
- Holds cannot be applied to COMPLETED requests; invalid/free-text hold reasons are rejected with HTTP 400.
- `RetentionAuditLogger.record` uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` to ensure denied audit records survive rollbacks.
- `RetentionExecutionRecorder.recordFailure` uses `Propagation.REQUIRES_NEW` to ensure failure execution records persist even when the sweep transaction rolls back.
- Internal exception details are sanitized upon sweep failure to prevent leaking database internals over HTTP.
- `retryDeletion` strictly validates owner facts: fails closed on empty facts or missing Identity fact (HTTP 422), and rejects retry if Identity is already completed without consuming bounded retry attempts.
- Retention sweep enforces bounded retries: 3 consecutive failed or stale executions block further automated sweep with HTTP 409 (`RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`).
- Stale detection via PostgreSQL advisory lock: prior committed `RUNNING` executions without an active transaction advisory lock transition to `STALE` with failure code `PRIOR_EXECUTION_STALE`.
- Concurrent sweep executions are blocked with HTTP 409 Conflict (`CONCURRENT_SWEEP_RUNNING`).
- Controller enforces `@PreAuthorize("hasRole('ADMIN')")`, rejecting anonymous callers (4xx) and non-admin roles (403 Forbidden).

### 3.2 Frontend Unit Tests (`vitest`)
Executed test suites:
- `features/retention/components/AdminRetentionGovernance.test.tsx` (13 tests)
- `features/auth/components/AdminWorkspace.test.tsx` (1 test)

```text
 ✓ features/retention/components/AdminRetentionGovernance.test.tsx (13 tests) 1387ms
 ✓ features/auth/components/AdminWorkspace.test.tsx (1 test) 124ms

 Test Files  2 passed (2)
      Tests  14 passed (14)
```

Verified UI behaviors:
- Sweep action disabled when job status is `RUNNING`, explaining execution in progress.
- A latest failed execution is shown as `FAILED`; retry remains available before exhaustion, while `manualEscalationRequired` disables sweep only after 3 consecutive failed/stale executions.
- Renders audit-safe guarantee banner and policy information without exposing secrets or PII.
- Explicitly renders unsupported capabilities as unavailable with disabled button (`MB-CONTENT-NOTIFICATION-RETENTION-001`).
- Allows triggering sweep for supported policies (`MB-IDENTITY-REPORTING-001`) and displays aggregate feedback.
- Deletions tab renders minimized `tombstone:...` identifiers; container contains no raw journal body, assessment answers, or emails.
- Hold badges displayed; retry button disabled when hold is active.
- Retry button disabled when bounded retry threshold (3/3) is exhausted.
- Expands per-owner completion facts table with cross-service evidence references.
- Hold modal opens, validates with safe reason selector, and dispatches hold request.

### 3.3 Frontend Lint & Type Checks
- `npm run typecheck`: **0 errors**.
- `npx eslint features/retention app/api/admin/retention tests/e2e/admin-retention-governance.spec.ts scripts/identity-e2e-server.mjs`: **0 errors, 0 warnings**.

### 3.4 Browser E2E Test (`tests/e2e/admin-retention-governance.spec.ts`)
- Executed: `npx playwright test tests/e2e/admin-retention-governance.spec.ts`
- Result: **2 passed (6.1s)**.
- Fixture: `scripts/identity-e2e-server.mjs` synchronized with synthetic retention policies (`MB-IDENTITY-REPORTING-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`), valid RFC 4122 UUID identifiers, and POST hold handling.
- Flow 1: Admin logs in -> Navigates to `/admin/retention` -> Verifies audit-safe banner -> Verifies policies & unavailable capability notice -> Triggers supported sweep -> Verifies aggregate feedback.
- Flow 2: Switches to Deletions tab -> Verifies tombstone identifiers -> Verifies hold badge and disabled retry on held request -> Verifies retry-exhausted badge and disabled retry on failed request -> Expands owner completion facts table -> Applies `OPERATIONAL_HOLD` via modal and confirms retry is blocked.

### 3.5 Real Database Transaction Verification Notes
- `RetentionExecutionRecorderTransactionTests` (3 tests) and `RetentionAuditLoggerTransactionTests` (3 tests) run in Spring's test framework with dynamic transaction managers to assert `REQUIRES_NEW` commits and `REQUIRED` rollbacks.
- `RetentionTransactionIntegrationTests` uses Testcontainers with a real PostgreSQL instance:
  - **LATEST RUN**: **NOT RUN** because Docker/Testcontainers was unavailable in the current environment.
  - **HISTORICAL PASS**: **5/5** verified on an earlier branch snapshot with active PostgreSQL Testcontainers.

---

## 4. Completion Notes

### 4.0 Jira Acceptance Criteria Evaluation

| Acceptance Criteria | Status | Evaluation Summary |
| :--- | :--- | :--- |
| **AC 1: Retention Policy Inventory & Sweep Execution** | **PASS** | Complete catalog of approved policies, versions, owners, status, last execution, and authoritative sweep execution for Identity report-artifacts with aggregate metrics; unsupported capabilities explicitly unavailable. |
| **AC 2: Audit-Safe Fact Tracking & Privacy Guarantees** | **PASS** | Bounded tombstone hashes (`tombstone:<sha256>`), strict UUID evidence format (`audit:identity:account-deleted:<uuid>`), authoritative failure reason (`ACCOUNT_NOT_FOUND`), fail-closed to null for unapproved codes/external reasons, zero leakage of clinical journals, assessments, or chat logs. |
| **AC 3: Gated Deletion Execution & Bounded Retries** | **PARTIAL / DEFERRED BY ARCHITECTURE** | **Delivered (Identity coordinator)**: Bounded retries (`maxRetries = 3`), schedule gating (`scheduledExecutionAt`), escalation states (`RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`), hold blocking, retry rejected when Identity completed.<br>**Deferred by architecture**: Production deletion intake and cross-service deletion execution across external data owners (`Care`, `Consultation`, `Journal AI`, `Community`, `Content`) remain deferred because no authoritative cross-service deletion contracts or events exist. Identity does not synthesize completion. |
| **AC 4: Fail-Closed Retention Holds** | **PASS** | Coordinator-level hold enforcement (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) blocking Identity coordination and retries; safe hold reasons enum (`STATUTORY_INQUIRY`, `PENDING_DISPUTE`, etc.); hold applied/released via dedicated endpoints. |
| **AC 5: Explicit Unavailability for Unsupported Capabilities** | **PASS** | Policies and capabilities without authoritative execution endpoints return HTTP 422 with clear notice; frontend renders disabled buttons with descriptive hints. |

### 4.1 Delivered vs Deferred Capabilities

#### Delivered
1. **Approved Retention Policy View**: Complete matrix of approved policies, versions, owners, job status, last execution, and safe aggregate metrics (`MB-IDENTITY-REPORTING-001`, `MB-IDENTITY-AUDIT-001`, `MB-PRIVACY-CARE-001`, `consultation-journey-v1`, `MB-DAILY-EMOTION-CHECK-IN-001`, `MB-COMMUNITY-RETENTION-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`).
2. **Explicit Unavailability**: Policies lacking automated execution endpoints are visibly flagged as explicitly unavailable with disabled actions and fail-closed API responses (HTTP 422).
3. **Traceable Deletion Requests**: Privacy-minimized identifiers (`tombstone:<sha256>`) prevent PII leakage while enabling cross-service correlation.
4. **Hold Enforcement**: Active holds (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) strictly block deletion retries.
5. **Bounded Retries & Escalation**: Retries are strictly bounded (`maxRetries = 3`); attempts beyond the threshold enter `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
6. **Existing Owner-Fact Governance**: The ADMIN view and retry logic expose and govern minimized owner facts on existing deletion rows. Identity only completes its own fact; external owner facts are never fabricated.
7. **Audit-Safe Transactions**: `RetentionAuditLogger` with `REQUIRES_NEW` propagation ensures denied audit events persist even when transactions roll back.
8. **Contract & Fixture Alignment**: Real policy IDs, HTTP POST for holds, and valid UUIDs synchronized across backend, OpenAPI, BFF, UI, and mock fixtures.

#### Deferred
1. **Universal Hard-Delete**: Explicitly prohibited by ticket scope and architectural boundary MB-583.
2. **Simulated Completion**: Deferred any simulated completion of external services that have not exposed authoritative retention APIs.
3. **Production Deletion Intake and External Facts**: No production code path creates a `DeletionRequestEntity`, and no approved deletion-request/owner-completion event contract or producer/consumer was found in the checked Identity and owner-service sources. Creating requests, fan-out, and authoritative external owner completion therefore remain unavailable; this ADMIN surface only governs existing rows and does not imply a live end-to-end deletion workflow.
4. **Manual Escalation Procedure**: Retry exhaustion is fail-closed, but no operator runbook or approved reset authority was found in the checked MB-673 evidence, Identity module documentation, or runbooks. No reset endpoint or procedure is implied by the status label.

---

## 5. Verification Commands

```powershell
# 1. Backend tests (all retention tests: service, controller, security)
cd "d:\DO AN\MentalBridge\mentalbridge-backend\identity-service"
.\mvnw.cmd test "-Dtest=AdminRetentionGovernance*"

# 2. Frontend unit tests
cd "d:\DO AN\MentalBridge\mentalbridge-frontend\mentalbridge"
npm run test:unit -- AdminRetentionGovernance AdminWorkspace

# 3. Frontend typecheck & lint
npm run typecheck
npx eslint features/retention app/api/admin/retention tests/e2e/admin-retention-governance.spec.ts scripts/identity-e2e-server.mjs
```
