# MB-673: [ADMIN] Govern Data Retention and Deletion Execution with Audit-Safe Evidence

## Executive Summary
This document provides audit-safe evidence and operational boundary documentation for task **MB-673**: *Govern data retention and deletion execution with audit-safe evidence*.

Under the MB-583 architecture, `Identity Service` coordinates deletion workflows and owns retention for accounts and authentication audit records. Each domain service (`Care`, `Consultation`, `Journal AI`, `Community`, `Content`) maintains autonomous authority over its domain data lifecycle. No universal hard-delete button is implemented, and no cross-service direct database access is permitted. All user deletion facts are traceable using privacy-minimized tombstone hashes (`tombstone:<sha256>`), with bounded retries, fail-closed hold enforcement, and explicit unavailability for unsupported capabilities.

---

## 1. Owner and Policy Authority Matrix

| Policy ID | Policy Name | Owner Service | Version | Retention Period | Sweep Capability | Manual Hold Supported | Safe Aggregate Outcome |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `IDENTITY_AUDIT_LOGS` | Chính sách bảo lưu nhật ký kiểm toán Identity | `IDENTITY` | 1.0.0 | 365 ngày | **ACTIVE** (Automated & Admin sweep supported) | Không áp dụng | Số bản ghi kiểm toán hết hạn đã xóa an toàn |
| `CARE_INACTIVE_ASSESSMENTS` | Chính sách bảo lưu kết quả đánh giá tâm lý Care | `CARE` | 1.0.0 | 730 ngày | **PARTIALLY_SUPPORTED** (Care domain-managed only) | **Có hỗ trợ** | Bản ghi đánh giá tâm lý lâm sàng không hoạt động |
| `CONSULTATION_COMPLETED_RECORDS` | Chính sách lưu trữ hồ sơ phiên tư vấn Consultation | `CONSULTATION` | 1.0.0 | 1825 ngày (5 năm) | **ACTIVE** (Consultation lifecycle service) | **Có hỗ trợ** | Hồ sơ lịch hẹn và phiên tư vấn hoàn tất |
| `JOURNAL_AI_EPHEMERAL_EMBEDDINGS` | Chính sách bảo lưu vector phân tích cảm xúc Journal AI | `JOURNAL_AI` | 1.0.0 | 30 ngày | **ACTIVE** (Ephemeral vector auto-expiration) | Không áp dụng | Vector embeddings và nhật ký suy luận AI tạm thời |
| `COMMUNITY_ORPHAN_POSTS` | Chính sách bảo lưu bài viết và tương tác mồ côi Community | `COMMUNITY` | 1.0.0 | 90 ngày | **ACTIVE** (Community anonymization sweep) | Không áp dụng | Bài viết, bình luận của tài khoản đã xóa (ẩn danh hóa) |
| `CONTENT_INBOX_PURGE` | Chính sách bảo lưu thông báo quản trị Content | `CONTENT` | 1.0.0 | 90 ngày | **UNAVAILABLE** (Explicitly Unavailable) | Không áp dụng | Thông báo nội dung không quan trọng |

### Authority & Architectural Rules
1. **Coordination vs Enforcement**: Identity coordinates deletion requests and orchestrates asynchronous owner facts. Each owner service processes its own deletion facts and emits audit evidence.
2. **Fail-Closed Capabilities**: Capabilities not supported by owner services (such as direct sweep invocation for Content inbox or manual hold override on ephemeral AI embeddings) are explicitly declared `UNAVAILABLE` and return HTTP 422 `CAPABILITY_UNSUPPORTED` rather than simulated success.
3. **Strict Privacy Bounds**: Under no circumstances are raw Journal texts, assessment responses, chat transcripts, audio/video streams, or system credentials returned or displayed in retention views.

---

## 2. Technical Implementation Details

### 2.1 Backend (`identity-service`)
- **Database Migration**: Liquibase changeset `006-retention-and-deletion-governance.sql` added:
  - `deletion_request`: Coordinates deletion requests, bounded retries (`retry_count`, `max_retries = 3`), escalation states (`NONE`, `RETRY_EXHAUSTED`, `MANUAL_ESCALATION_REQUIRED`), hold state (`hold_type`, `hold_reason`, `held_at`), and account status.
  - `deletion_owner_fact`: Records per-service completion facts, evidence references, and failure reasons across `IDENTITY`, `CARE`, `CONSULTATION`, `JOURNAL_AI`, `COMMUNITY`, `CONTENT`.
  - `retention_execution_record`: Records sweep outcomes (`items_evaluated`, `records_purged`, `tombstones_created`, `holds_honored`).
- **Domain Service (`AdminRetentionGovernanceService`)**:
  - Implements approved policy catalog and execution dispatch.
  - Enforces fail-closed rules: blocks deletion execution if active hold (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) is present (`DeletionHoldActiveException`).
  - Limits retry attempts to bounded threshold (`max_retries = 3`), transitioning exhausted requests to `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
  - Generates minimized tombstone references (`tombstone:sha256Hex`).
  - Records audit trail for policy sweeps, hold applications, hold releases, and deletion retries.
- **REST Controller (`AdminRetentionGovernanceController`)**:
  - Secured with `@PreAuthorize("hasRole('ADMIN')")`.
  - Endpoints:
    - `GET /api/v1/admin/retention/policies`
    - `POST /api/v1/admin/retention/policies/{policyId}/sweep`
    - `GET /api/v1/admin/retention/deletions`
    - `POST /api/v1/admin/retention/deletions/{requestId}/retry`
    - `PUT /api/v1/admin/retention/deletions/{requestId}/holds`
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
    - `app/api/admin/retention/deletions/[requestId]/holds/route.ts`
- **UI Components**:
  - `AdminRetentionGovernance.tsx`:
    - Policy tab: Displays policy version, owner, status, last execution, safe aggregate counts, and explicitly unavailable indicators with disabled sweep buttons.
    - Deletions tab: Minimized identifiers (`tombstone:<hash>`), status/hold badges, retry counter (`retryCount / maxRetries`), expandable owner facts table with evidence references, and hold management modal.
    - Audit-Safe Guarantee Banner: Explicit notice affirming privacy guarantees and absence of raw journal, assessment, or chat text.
  - Integrated into `AdminWorkspace.tsx` under section `/admin/retention` ("Bảo lưu & xóa dữ liệu").

---

## 3. Test & Verification Results

### 3.1 Backend Test Results (`mvnw.cmd test`)
Executed test suites:
- `AdminRetentionGovernanceServiceTests` (9 tests)
- `AdminRetentionGovernanceControllerTests` (9 tests)

```text
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.812 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceServiceTests
[INFO] Running com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.487 s -- in com.mentalbridge.identity.retention.AdminRetentionGovernanceControllerTests
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Key verified test assertions:
- Service returns all 6 approved policies with authoritative metadata.
- Triggering sweep for unsupported capability (`CONTENT_INBOX_PURGE`) throws `UnsupportedRetentionCapabilityException`.
- Applying `LEGAL_HOLD` / `OPERATIONAL_HOLD` blocks retries with `DeletionHoldActiveException`.
- Bounded retry increments count and throws `DeletionRetryExhaustedException` at max retries (3), transitioning state to `RETRY_EXHAUSTED`.
- Subject references strictly adhere to `tombstone:<sha256Hex>`.
- Controller enforces HTTP 403 for non-admin callers, HTTP 422 for unsupported capabilities, and HTTP 409 for active holds / exhausted retries.

### 3.2 Frontend Unit Tests (`vitest`)
Executed test suites:
- `features/retention/components/AdminRetentionGovernance.test.tsx` (8 tests)
- `features/auth/components/AdminWorkspace.test.tsx` (1 test)

```text
 ✓ features/retention/components/AdminRetentionGovernance.test.tsx (8 tests) 735ms
 ✓ features/auth/components/AdminWorkspace.test.tsx (1 test) 149ms

 Test Files  2 passed (2)
      Tests  9 passed (9)
```

Verified UI behaviors:
- Renders audit-safe guarantee banner and policy information without exposing secrets or PII.
- Explicitly renders unsupported capabilities as unavailable with disabled button.
- Allows triggering sweep for supported policies and displays aggregate feedback.
- Deletions tab renders minimized `tombstone:...` identifiers; container contains no raw journal body, assessment answers, or emails.
- Hold badges displayed; retry button disabled when hold is active.
- Retry button disabled when bounded retry threshold (3/3) is exhausted.
- Expands per-owner completion facts table with cross-service evidence references.
- Hold modal opens, validates, and dispatches hold request.

### 3.3 Frontend Lint & Type Checks
- `npx eslint features/retention app/api/admin/retention features/auth/components/AdminWorkspace.tsx features/auth/components/AdminWorkspace.test.tsx features/auth/api/identity-contract.ts lib/auth/identity-client.ts lib/auth/identity-validation.ts`
  -> **0 errors, 0 warnings**.

### 3.4 Browser E2E Test (`tests/e2e/admin-retention-governance.spec.ts`)
- Fixture: `scripts/identity-e2e-server.mjs` updated with synthetic retention policies and deletion requests.
- Flow 1: Admin logs in -> Navigates to `/admin/retention` -> Verifies audit-safe banner -> Verifies policies & unavailable capability notice -> Triggers supported sweep -> Verifies aggregate feedback.
- Flow 2: Switches to Deletions tab -> Verifies tombstone identifiers -> Verifies hold badge and disabled retry on held request -> Verifies retry-exhausted badge and disabled retry on failed request -> Expands owner completion facts table -> Applies `OPERATIONAL_HOLD` via modal and confirms retry is blocked.

---

## 4. Completion Notes

### Delivered vs Deferred Capabilities

#### Delivered
1. **Approved Retention Policy View**: Complete matrix of approved policies, versions, owners, job status, last execution, and safe aggregate metrics.
2. **Explicit Unavailability**: Policies lacking automated execution endpoints (`CONTENT_INBOX_PURGE`, `CARE_INACTIVE_ASSESSMENTS`) are visibly flagged as explicitly unavailable with disabled actions and fail-closed API responses (HTTP 422).
3. **Traceable Deletion Requests**: Privacy-minimized identifiers (`tombstone:<sha256>`) prevent PII leakage while enabling cross-service correlation.
4. **Hold Enforcement**: Active holds (`LEGAL_HOLD`, `OPERATIONAL_HOLD`, `DISPUTE_HOLD`) strictly block deletion retries.
5. **Bounded Retries & Escalation**: Retries are strictly bounded (`maxRetries = 3`); attempts beyond the threshold enter `RETRY_EXHAUSTED` / `MANUAL_ESCALATION_REQUIRED`.
6. **Owner Completion Facts**: Asynchronous owner facts report per-service progress and audit evidence without cross-owner direct DB access.

#### Deferred
1. **Universal Hard-Delete**: Explicitly prohibited by ticket scope and architectural boundary MB-583.
2. **Simulated Completion**: Deferred any simulated completion of external services that have not exposed authoritative retention APIs.

---

## 5. Verification Commands

```powershell
# 1. Backend tests
cd "d:\DO AN\MentalBridge\mentalbridge-backend"
.\mvnw.cmd test "-Dtest=AdminRetentionGovernanceServiceTests,AdminRetentionGovernanceControllerTests"

# 2. Frontend unit tests
cd "d:\DO AN\MentalBridge\mentalbridge-frontend\mentalbridge"
npm run test:unit -- AdminRetentionGovernance AdminWorkspace

# 3. Frontend linting
npx eslint features/retention app/api/admin/retention features/auth/components/AdminWorkspace.tsx features/auth/components/AdminWorkspace.test.tsx features/auth/api/identity-contract.ts lib/auth/identity-client.ts lib/auth/identity-validation.ts
```
