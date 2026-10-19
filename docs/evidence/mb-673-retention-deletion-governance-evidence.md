# MB-673: [ADMIN] Govern Data Retention and Deletion Execution with Audit-Safe Evidence

## Executive Summary
This document provides audit-safe evidence and operational boundary documentation for task **MB-673**: *Govern data retention and deletion execution with audit-safe evidence*.

Under the MB-583 architecture, `Identity Service` coordinates deletion workflows and owns retention for accounts and authentication audit records. Each domain service (`Care`, `Consultation`, `Journal AI`, `Community`, `Content`) maintains autonomous authority over its domain data lifecycle. No universal hard-delete button is implemented, and no cross-service direct database access is permitted. All user deletion facts are traceable using privacy-minimized tombstone hashes (`tombstone:<sha256>`), with bounded retries, fail-closed hold enforcement, and explicit unavailability for unsupported capabilities.

---

## 1. Owner and Policy Authority Matrix

| Policy ID | Policy Name | Owner Service | Version | Retention Period | Sweep Capability | Manual Hold Supported | Safe Aggregate Outcome |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `MB-IDENTITY-REPORTING-001` | Chính sách bảo lưu báo cáo phân tích tổng hợp | `IDENTITY` | 1.0 | 90 ngày | **ACTIVE** (Automated & Admin sweep supported via authoritative metrics) | Không áp dụng | Số lượng tệp trích xuất báo cáo phân tích hết hạn đã dọn dẹp |
| `MB-IDENTITY-AUDIT-001` | Chính sách bảo lưu sự kiện kiểm toán danh tính | `IDENTITY` | 1.0 | 365 ngày | **ACTIVE** (Partitioned auto-retention) | **Có hỗ trợ** | Nhật ký kiểm toán danh tính tối thiểu hóa |
| `MB-PRIVACY-CARE-001` | Chính sách bảo lưu hồ sơ lâm sàng và phiên chăm sóc | `CARE` | 1.0 | 730 ngày | **PARTIALLY_SUPPORTED** (Care domain-managed only) | **Có hỗ trợ** | Bản ghi đánh giá tâm lý lâm sàng và hồ sơ phiên chăm sóc |
| `consultation-journey-v1` | Chính sách bảo lưu hành trình tư vấn chuyên gia | `CONSULTATION` | 1.0 | 365 ngày | **PARTIALLY_SUPPORTED** (Consultation lifecycle service) | **Có hỗ trợ** | Lịch sử cuộc hẹn và ghi chú tư vấn hoàn tất |
| `MB-DAILY-EMOTION-CHECK-IN-001` | Chính sách bảo lưu điểm danh cảm xúc hàng ngày | `JOURNAL_AI` | 1.0 | 180 ngày | **PARTIALLY_SUPPORTED** (Journal AI auto-expiration) | Không áp dụng | Nhật ký cảm xúc định kỳ và vector suy luận AI |
| `MB-COMMUNITY-RETENTION-001` | Chính sách bảo lưu phương tiện bài đăng cộng đồng | `COMMUNITY` | 1.0 | 7 ngày | **PARTIALLY_SUPPORTED** (Community media sweep) | Không áp dụng | Tệp đính kèm tạm thời bài đăng cộng đồng |
| `MB-CONTENT-NOTIFICATION-RETENTION-001` | Chính sách bảo lưu hộp thư thông báo | `CONTENT` | 1.0 | 90 ngày | **UNAVAILABLE** (Explicitly Unavailable) | Không áp dụng | Hộp thư thông báo nội dung không quan trọng |

### Authority & Architectural Rules
1. **Coordination vs Enforcement**: Identity coordinates deletion requests and orchestrates asynchronous owner facts. Each owner service processes its own deletion facts and emits audit evidence.
2. **No Fabricated Facts (P0-A)**: Identity only confirms completion of its own internal fact (`IDENTITY`). External facts (`CARE`, `CONSULTATION`, `JOURNAL_AI`, `COMMUNITY`, `CONTENT`) remain `PENDING` or `UNAVAILABLE` until authoritative cross-service contracts or events report completion. Identity never fabricates fake evidence references or marks external facts completed.
3. **Authoritative Sweep Metrics (P0-B)**: Retention sweeps query authoritative database metrics directly (via `PlatformReportProcessor.purgeExpiredArtifactsWithMetrics()`), eliminating all fabricated or hardcoded offsets.
4. **Safe Deletion Retry (P0-C)**: Retry validates lifecycle state, blocks requests scheduled in the future (`scheduledExecutionAt.isAfter(now)`), active holds, or exhausted retry limits. Retries are strictly bounded (`maxRetries = 3`).
5. **Fail-Closed Capabilities (AC 5)**: Capabilities not supported by owner services (e.g. direct sweep invocation on Care records or Content notifications) are explicitly declared `UNAVAILABLE` / `PARTIALLY_SUPPORTED` and fail closed (HTTP 422 `CAPABILITY_UNSUPPORTED`) rather than simulating success.
6. **Strict Privacy Bounds (AC 2)**: Under no circumstances are raw Journal texts, assessment responses, chat transcripts, audio/video streams, or system credentials returned or displayed in retention views.
7. **Safe Hold Reason Codes (P1-D)**: Hold reasons are strictly restricted to a whitelist of safe reason codes (`STATUTORY_INQUIRY`, `PENDING_DISPUTE`, `ACTIVE_SAFETY_REVIEW`, `SCHEDULED_APPOINTMENT_ACTIVE`, `USER_CONTESTED`, `AUDIT_COMPLIANCE_HOLD`) preventing free-text PII leakage. Hold cannot be applied to `COMPLETED` requests. Releasing a hold restores the request to its legitimate lifecycle state (`FAILED` if retry exhausted, `IN_PROGRESS` if retry > 0, `PENDING` if retry == 0).
8. **Audit Transaction Isolation (P1-E)**: Denied audit events are committed using `RetentionAuditLogger` with `@Transactional(propagation = Propagation.REQUIRES_NEW)`, ensuring denied audit records persist even when the caller transaction rolls back due to a business exception.
9. **Deletion Evidence Preservation (P1-I)**: Foreign key `account_id` on `deletion_request` does NOT cascade delete on account removal, ensuring immutable audit evidence and traceability are permanently preserved.

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

### 3.1 Backend Test Results (`mvnw.cmd test`)
Executed test suites:
- `AdminRetentionGovernanceServiceTests` (11 tests)
- `AdminRetentionGovernanceControllerTests` (11 tests)
- `AdminRetentionGovernanceSecuritySliceTests` (5 tests)

```text
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.958 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceSecuritySliceTests
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.953 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceSecuritySliceTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.459 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Key verified test assertions:
- Service returns all 7 approved policies with authoritative metadata matching documented repository contracts.
- Triggering sweep for unsupported capability (`MB-PRIVACY-CARE-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`) throws `UnsupportedRetentionCapabilityException` (HTTP 422) and logs DENIED audit.
- Authoritative reporting sweep metrics evaluated directly from database (`PlatformReportProcessor.purgeExpiredArtifactsWithMetrics()`).
- Applying `LEGAL_HOLD` / `OPERATIONAL_HOLD` blocks retries with `DeletionHoldActiveException` (HTTP 409) and logs DENIED audit.
- Bounded retry increments count and throws `DeletionRetryExhaustedException` at max retries (3), transitioning state to `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
- Non-fabrication: Identity only marks its own fact completed; CARE/CONSULTATION/JOURNAL_AI/COMMUNITY/CONTENT facts remain PENDING. Overall request remains IN_PROGRESS until all facts complete.
- Holds cannot be applied to COMPLETED requests; invalid/free-text hold reasons are rejected with HTTP 400.
- `RetentionAuditLogger.record` uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` to ensure denied audit records survive rollbacks.
- Controller enforces `@PreAuthorize("hasRole('ADMIN')")`, rejecting anonymous callers (4xx) and non-admin roles (403 Forbidden).

### 3.2 Frontend Unit Tests (`vitest`)
Executed test suites:
- `features/retention/components/AdminRetentionGovernance.test.tsx` (8 tests)
- `features/auth/components/AdminWorkspace.test.tsx` (1 test)

```text
 ✓ features/retention/components/AdminRetentionGovernance.test.tsx (8 tests) 749ms
 ✓ features/auth/components/AdminWorkspace.test.tsx (1 test) 145ms

 Test Files  2 passed (2)
      Tests  9 passed (9)
```

Verified UI behaviors:
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
- Fixture: `scripts/identity-e2e-server.mjs` synchronized with synthetic retention policies (`MB-IDENTITY-REPORTING-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`), valid RFC 4122 UUID identifiers, and POST hold handling.
- Flow 1: Admin logs in -> Navigates to `/admin/retention` -> Verifies audit-safe banner -> Verifies policies & unavailable capability notice -> Triggers supported sweep -> Verifies aggregate feedback.
- Flow 2: Switches to Deletions tab -> Verifies tombstone identifiers -> Verifies hold badge and disabled retry on held request -> Verifies retry-exhausted badge and disabled retry on failed request -> Expands owner completion facts table -> Applies `OPERATIONAL_HOLD` via modal and confirms retry is blocked.

---

## 4. Completion Notes

### Delivered vs Deferred Capabilities

#### Delivered
1. **Approved Retention Policy View**: Complete matrix of approved policies, versions, owners, job status, last execution, and safe aggregate metrics (`MB-IDENTITY-REPORTING-001`, `MB-IDENTITY-AUDIT-001`, `MB-PRIVACY-CARE-001`, `consultation-journey-v1`, `MB-DAILY-EMOTION-CHECK-IN-001`, `MB-COMMUNITY-RETENTION-001`, `MB-CONTENT-NOTIFICATION-RETENTION-001`).
2. **Explicit Unavailability**: Policies lacking automated execution endpoints are visibly flagged as explicitly unavailable with disabled actions and fail-closed API responses (HTTP 422).
3. **Traceable Deletion Requests**: Privacy-minimized identifiers (`tombstone:<sha256>`) prevent PII leakage while enabling cross-service correlation.
4. **Hold Enforcement**: Active holds (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) strictly block deletion retries.
5. **Bounded Retries & Escalation**: Retries are strictly bounded (`maxRetries = 3`); attempts beyond the threshold enter `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
6. **Owner Completion Facts**: Asynchronous owner facts report per-service progress and audit evidence without cross-owner direct DB access. Non-fabrication guarantees that Identity only completes its own boundary.
7. **Audit-Safe Transactions**: `RetentionAuditLogger` with `REQUIRES_NEW` propagation ensures denied audit events persist even when transactions roll back.
8. **Contract & Fixture Alignment**: Real policy IDs, HTTP POST for holds, and valid UUIDs synchronized across backend, OpenAPI, BFF, UI, and mock fixtures.

#### Deferred
1. **Universal Hard-Delete**: Explicitly prohibited by ticket scope and architectural boundary MB-583.
2. **Simulated Completion**: Deferred any simulated completion of external services that have not exposed authoritative retention APIs.

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
