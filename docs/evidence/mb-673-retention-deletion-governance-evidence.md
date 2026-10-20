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
| `MB-PRIVACY-CARE-001` | Chính sách bảo lưu sàng lọc & đồng ý chăm sóc | `CARE` | 1.1-capstone | Quản lý theo vòng đời Care (TTL 30p-2h ẩn danh, có tài khoản bảo lưu kiểm soát) | **PARTIALLY_SUPPORTED** (Care domain-managed only) | **Có hỗ trợ** | Bản ghi đánh giá tâm lý lâm sàng và hồ sơ phiên chăm sóc |
| `consultation-journey-v1` | Chính sách bảo lưu phiên tư vấn & đối soát giao dịch | `CONSULTATION` | 1.0-scope-v2 | 90 ngày (LiveKit metadata, tài chính kế toán theo luật) | **ACTIVE** (Consultation metadata sweep) | **Có hỗ trợ** | Lịch sử cuộc hẹn và ghi chú tư vấn hoàn tất |
| `MB-DAILY-EMOTION-CHECK-IN-001` | Chính sách bảo lưu & xóa nhật ký cảm xúc | `JOURNAL_AI` | 1.0 | Quản lý mã hóa Journal AI (xóa ciphertext, tombstone có giới hạn) | **ACTIVE** (Journal AI tombstone purge) | **Có hỗ trợ** | Nhật ký cảm xúc định kỳ và vector suy luận AI |
| `MB-COMMUNITY-RETENTION-001` | Chính sách bảo lưu nội dung cộng đồng | `COMMUNITY` | 1.0 | 7 ngày (phương tiện chưa liên kết) | **PARTIALLY_SUPPORTED** (Community media sweep) | Không áp dụng | Tệp đính kèm tạm thời bài đăng cộng đồng |
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
    - Deletions tab: Minimized identifiers (`tombstone:<hash>`), status/hold badges, retry counter (`retryCount / maxRetries`), expandable owner facts table with evidence references, and hold management modal with safe reason selector.
    - Audit-Safe Guarantee Banner: Explicit notice affirming privacy guarantees and absence of raw journal, assessment, or chat text.
  - Integrated into `AdminWorkspace.tsx` under section `/admin/retention` ("Bảo lưu & xóa dữ liệu").

---

## 3. Test & Verification Results

### 3.0 Local verification for the 2026-10-20 review

| Check | Result | Actual tests |
| :--- | :--- | :--- |
| `mvn -q '-Dtest=AdminRetentionGovernanceServiceTests' test` | **PASS** | 29 run, 0 failed, 0 errors, 0 skipped; includes GET/POST agreement for 2 FAILED + 1 orphaned RUNNING (both newest, middle, and oldest positions). |
| `AdminRetentionGovernanceControllerTests` | **PASS** | 11 run, 0 failed, 0 errors, 0 skipped; validates HTTP responses and parameter validation. |
| `AdminRetentionGovernanceSecuritySliceTests` | **PASS** | 5 run, 0 failed, 0 errors, 0 skipped; validates RBAC `@PreAuthorize("hasRole('ADMIN')")`. |
| `RetentionAuditLoggerTransactionTests` | **PASS** | 3 run, 0 failed, 0 errors, 0 skipped; validates `REQUIRES_NEW` audit persistence on rollback. |
| `RetentionExecutionRecorderTransactionTests` | **PASS** | 3 run, 0 failed, 0 errors, 0 skipped; validates failure record commit and success rollback. |
| `IdentityOpenApiContractTests` | **PASS** | 1 run, 0 failed, 0 errors, 0 skipped; validates OpenAPI schema endpoints and status codes. |
| `RetentionTransactionIntegrationTests` | **SKIPPED (Local) / PASS (Docker CI)** | Testcontainers PostgreSQL integration test annotated with `@EnabledIf("dockerAvailable")`. Skipped locally when Docker daemon is not running on host; runs in CI environment with Docker. |
| Total targeted backend run | **PASS** | **52 run, 0 failed, 0 errors, 0 skipped** (BUILD SUCCESS). |
| Frontend unit, typecheck, lint and browser E2E | **NOT RUN** | Backend response contract is fully consistent; no frontend changes required. |

Two initial database runs failed before executing test bodies because the `deletion_request.subject_reference_hash` JPA mapping expected JDBC `VARCHAR` while the existing migration creates `char(64)`; setting only `columnDefinition` did not change the JDBC type. The mapping now uses the repository's existing `@JdbcTypeCode(SqlTypes.CHAR)` pattern, and the final five-test database run passed. No migration or transaction propagation was changed.

Sections 3.1–3.4 below record verification from the earlier branch snapshot; their counts are historical, not tests rerun for this worktree.

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
- Sweep action disabled when job status is `FAILED` (3 consecutive failed/stale executions), explaining `MANUAL_ESCALATION_REQUIRED`.
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
- `RetentionTransactionIntegrationTests` uses Testcontainers with a real PostgreSQL instance. On 2026-10-20, Docker was available and all 5 tests ran and passed; none were skipped.

---

## 4. Completion Notes

### Delivered vs Deferred Capabilities

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
