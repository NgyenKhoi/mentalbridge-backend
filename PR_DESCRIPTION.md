## Tóm tắt thay đổi

- Loại thay đổi: `feat`
- Module/owner: `content-notification-service` / Content team
- Hành vi hoặc vấn đề được giải quyết: Implement MB-180 backend provider để cung cấp resource catalogue cho screening result pages
- Lý do cần thay đổi: Assessment result pages cần hiển thị reviewed resources (articles, videos, support links) cho user sau khi hoàn thành screening

**⚠️ SCOPE: Backend-only implementation**

PR này chỉ implement **backend provider** cho MB-180. Frontend integration (BFF route, typed client, result page rendering, Playwright tests) sẽ được deliver trong **PR riêng tại frontend repository**.

## Implementation contract

- Issue/TODO liên quan: `Closes MB-197, MB-198, MB-199, MB-200` (sub-tasks của MB-180)
- Contract active/proposal: 
  - OpenAPI `GET /api/v1/resources` marked as `implemented`
  - Publication governance filters documented in operation description
- ADR/domain rule liên quan: 
  - Content/Notification là source of truth cho resource catalogue
  - Chỉ serve resources có status=PUBLISHED, reviewed, active (trong hiệu lực)
  - Neutral fallback khi database unavailable hoặc empty results
- Migration và data dictionary:
  - `V6__create_resource_table.sql` tạo bảng `resource` với publication governance fields
  - Data dictionary updated với resource entity và publication workflow
- Thư mục được phép thay đổi:
  - `content-notification-service/src/main/java/com/mentalbridge/content/resource/`
  - `content-notification-service/src/main/resources/db/migration/`
  - `content-notification-service/src/test/java/com/mentalbridge/content/resource/`
  - `content-notification-service/openapi.yaml`
  - `content-notification-service/README.md`
  - Docs: `CONTRACTS.md`, `DATA_DICTIONARY.md`, `PROVIDER_BOUNDARIES.md`
- Ngoài phạm vi:
  - ❌ Frontend BFF route (Next.js API route)
  - ❌ Typed resource client
  - ❌ Result page rendering (anonymous + authenticated journeys)
  - ❌ Frontend tests (route/component tests)
  - ❌ Playwright E2E tests
  - ❌ Manual accessibility/responsive review evidence
- Ví dụ acceptance/edge case:
  ```bash
  # Published, reviewed, active resource → trả về
  GET /api/v1/resources?category=ARTICLE&limit=10
  → 200 OK, items array chứa resources
  
  # Unpublished hoặc chưa review → không trả về
  # Future effective_at hoặc expired → không trả về
  
  # Database unavailable → neutral fallback
  GET /api/v1/resources
  → 200 OK, items=[], hasMore=false
  
  # Invalid params → validation error
  GET /api/v1/resources?limit=abc
  → 400 Bad Request với Problem Details
  ```

## Ảnh hưởng và tương thích

- REST/Kafka/WebSocket compatibility:
  - New endpoint `GET /api/v1/resources` - backward compatible (no breaking changes)
  - Response format: `{ items: [], hasMore: boolean, nextCursor?: string }`
  - Pagination dùng composite cursor (created_at, id) - stable ordering
- Data/rollback hoặc forward-migration plan:
  - Migration V6 tạo bảng mới `resource` - rollback safe (drop table)
  - Không thay đổi existing tables hay data
- Authorization, privacy và dữ liệu nhạy cảm:
  - Endpoint public (không yêu cầu auth) - phù hợp cho anonymous screening results
  - Không chứa PII - chỉ trả published content URLs và metadata
  - Future: có thể cần personalization dựa trên user profile (tracked in backlog)
- Configuration/operations:
  - Không cần thêm config mới
  - Database connection pool existing (shared với content/notification tables)
- Caller/consumer đã kiểm tra:
  - ✅ Backend contract: Provider boundary tests verify response structure
  - ❌ Frontend consumer: Sẽ được test trong frontend PR

## Verification

| Command | Environment | Result |
| --- | --- | --- |
| `./mvnw clean test` | local | **22 unit tests pass** |
| `./mvnw verify -P integration-test` | local + Testcontainers PostgreSQL | **8 integration tests pass** |
| `./mvnw spring-boot:run` + manual `curl` | local | Endpoint returns 200, filters work correctly |
| GitHub Actions CI checks | GitHub Actions | **All 7 backend checks pass** at commit `dfe2397` |

**Integration test coverage:**
- ✅ Published + reviewed + active resources được trả về
- ✅ Unpublished, unreviewed, future-effective, expired resources bị lọc
- ✅ Composite cursor pagination với tied timestamps
- ✅ Category filtering
- ✅ Empty results trả neutral fallback
- ✅ Database connection failure → graceful neutral fallback
- ✅ Validation errors → HTTP 400 Problem Details

**Chưa verify (ngoài scope PR này):**
- Frontend BFF integration
- Result page rendering với resources
- Empty/unavailable/timeout UI states
- Playwright cross-service scenarios

## Đồng bộ base

- [x] Đã `git fetch origin` và kiểm tra divergence với `origin/dev` trước khi code.
- [x] Đã fetch/đồng bộ lại ngay trước commit/push/PR.
- [x] Đã review toàn bộ `origin/dev...HEAD` và không đưa file ngoài phạm vi vào PR.

## Checklist trước merge

- [x] Tiêu đề theo Conventional Commits: `feat(content): implement reviewed resources provider (MB-180 backend)`
- [x] Owner, source of truth, contract status và out-of-scope đã rõ.
- [x] Controller/gateway thay đổi cùng OpenAPI và provider boundary tests.
- [ ] Event/WebSocket thay đổi cùng versioned schema và producer/consumer tests. *(N/A - không có event changes)*
- [x] Migration thay đổi cùng data dictionary và constraint/mapping tests.
- [ ] Configuration thay đổi cùng `.env.example`, README và configuration tests khi semantics thay đổi. *(N/A - không có config changes)*
- [x] Đã kiểm tra validation, authorization, conflict/concurrency, duplicate/retry và dependency failure phù hợp phạm vi.
- [x] Không log/event/error token, password, raw journal/chat, assessment answer, private URL hoặc provider payload nhạy cảm.
- [x] Không thêm layer/interface/shared abstraction nếu chưa có boundary hoặc hành vi cụ thể.
- [x] Required GitHub quality gate đã pass.

---

## DoD Status cho MB-180

### ✅ Backend Provider (PR này)

- ✅ **MB-197**: Resource repository, service, controller với `GET /api/v1/resources`
- ✅ **MB-198**: Chỉ serve active, reviewed, published resources (filters đầy đủ)
- ✅ **MB-199**: Neutral empty/unavailable fallback states
- ✅ **MB-200**: Unit tests (22) + PostgreSQL Testcontainers integration tests (8)
- ✅ Content/Notification remains resource catalogue authority
- ✅ Publication governance filters (status, reviewed_at, reviewed_by, effective_at, expires_at)
- ✅ Neutral backend responses for empty/database-unavailable cases
- ✅ Backend CI passes (all 7 checks green)

### ❌ Frontend Integration (Separate PR required)

- ❌ Server-side BFF route (Next.js API route) với Content base URL/timeout config
- ❌ Typed resource client/parser
- ❌ Rendering resources sau anonymous screening results
- ❌ Rendering resources sau authenticated screening results  
- ❌ Explicit empty/unavailable/malformed/timeout/unauthorized UI states
- ❌ Unavailable future-feature labels (subscription, consultation)
- ❌ Frontend route/component tests
- ❌ Frontend quality gate (format, lint, typecheck, build)
- ❌ Playwright E2E tests với deterministic fixtures
- ❌ Manual safety-copy/accessibility/responsive evidence

---

## Review Response

**Về yêu cầu frontend implementation:**

PR này được scope là **backend-only provider** cho MB-180. Lý do:

1. Repository này chỉ chứa backend services (Spring Boot microservices)
2. Frontend code (Next.js) nằm ở repository riêng (chưa được link trong review)
3. Backend provider đã complete đầy đủ contract và tests theo DoD backend portion

**Next steps:**

1. Merge PR này sau khi backend reviewers approve
2. Frontend team tạo PR riêng tại frontend repo để implement:
   - BFF route consuming `GET /api/v1/resources`
   - Result page rendering
   - UI states và error handling
   - Frontend tests + Playwright evidence
3. Frontend PR sẽ reference MB-180 và close story khi hoàn tất

**Tại sao không gộp chung?**

- Backend và frontend là separate repositories với separate CI/CD pipelines
- Backend contract đã stable và ready cho frontend consume
- Parallel development: frontend có thể develop với mocked responses trong khi backend đã merge

Nếu reviewer yêu cầu frontend phải cùng PR, xin cung cấp link đến frontend repository để có thể implement phần còn lại.
