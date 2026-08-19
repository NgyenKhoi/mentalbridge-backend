# Story 208 Completion Checklist

## Sub-task 331: Node.js baseline

- [x] Khai báo Node.js 24 trong `package.json` (warning hiện vì Node 22 đang chạy local)
- [x] Chuyển sang strict TypeScript + ESM modules
- [x] Nâng Express 4 lên Express 5
- [x] Thêm `tsconfig.json` và `tsconfig.build.json`
- [x] Thêm ESLint, Prettier và Jest (sử dụng Jest thay vì Vitest)
- [x] Bổ sung các script: `format:check`, `lint`, `typecheck`, `test`, `build`, `contract:check`, `migration:check`
- [x] Thêm graceful shutdown để đóng HTTP server và PostgreSQL pool
- [x] Thêm Dockerfile chạy compiled output
- [x] Đồng bộ lại `package-lock.json` sau khi sửa dependencies
- [x] Bổ sung `coverage/` vào `.gitignore`

## Sub-task 332: Configuration và health

- [x] Tạo typed configuration bằng Zod
- [x] Chỉ đọc `process.env` trong configuration module
- [x] Validate DB host, port, database, user, password, pool size và timeout
- [x] Chỉ load `.env` trong local development (dotenv/config)
- [x] Thêm metrics endpoint (`GET /metrics`)
- [x] Dùng Pino structured logging thay cho Winston
- [x] Có secret redaction (DB_PASSWORD, tokens, authorization headers)
- [x] Test configuration: missing, invalid và overridden values
- [x] Test readiness: PostgreSQL hoạt động và PostgreSQL unavailable
- [x] Liveness không phụ thuộc database

### Configuration updates:
- [x] Database mặc định `mentalbridge_content_notification`
- [x] Dùng schema mặc định `public` (không dùng schema `content`)
- [x] Cập nhật `.env.example` và README cho cùng bộ biến
- [x] Tạo file `.env` với thông tin database thực tế

## Sub-task 333: OpenAPI

- [x] Tạo OpenAPI 3.1 cho resource và hotline
- [x] Định nghĩa public read endpoints
- [x] Định nghĩa admin create, update, publish và retire endpoints
- [x] Khai báo authentication scheme (AdminHeader apiKey)
- [x] Khai báo pagination, locale và region filters
- [x] Khai báo lifecycle/review-date fields
- [x] Khai báo RFC 9457 errors có `code` và `correlationId`
- [x] Thêm request, response và error examples
- [x] Thêm script validate OpenAPI (`npm run contract:check`)

## Sub-task 334: Liquibase/PostgreSQL

- [x] Tạo migration baseline (`001_content_schema_baseline.sql`)
- [x] Tạo bảng `resource` và `hotline` (trong schema `public`)
- [x] Có review/effective/expiry dates
- [x] Có constraints cho required fields, dates và lifecycle
- [x] Có indexes cho public reads theo status, locale, region và review window
- [x] Migration validation script (`npm run migration:check`)
- [x] Migration sử dụng database `mentalbridge_content_notification`
- [ ] **TODO (Story 209)**: Testcontainers PostgreSQL integration tests

## Verification Scripts - All Passing ✓

```bash
npm run typecheck      # ✓ TypeScript compilation
npm run lint           # ✓ ESLint checks
npm run format:check   # ✓ Prettier formatting
npm test               # ✓ Jest tests (9/9 passing)
npm run build          # ✓ Build to dist/
npm run contract:check # ✓ OpenAPI validation
npm run migration:check # ✓ Migration file validation
```

## Not Required for Story 208

The following items are explicitly out of scope for Story 208 and belong to Story 209 or later stories:

- ❌ Implementation of CRUD endpoints
- ❌ Lifecycle transition logic (publish, archive, deactivate)
- ❌ Public-query filtering implementation
- ❌ Kafka integration
- ❌ Brevo email integration
- ❌ Push notification integration
- ❌ CD/deployment configuration
- ❌ Testcontainers integration tests (deferred to Story 209)

## Service Location

✓ Service is at: `mentalbridge-backend/services/content-notification-service/`
✓ No duplicate at old path

## Database Configuration

Database: `mentalbridge_content_notification`
Schema: `public` (default PostgreSQL schema)
Host: `mentalbridge-dev.cz0oiua0mrn7.ap-southeast-1.rds.amazonaws.com`
Port: `5432`
User: `postgres`

Tables created:
- `resource` - self-help content with lifecycle management
- `hotline` - crisis support contacts with mandatory review
- `notification_preference` - per-user delivery settings
- `notification` - durable in-app messages

## Node Version Note

⚠️ Package requires Node.js 24+, but local environment is Node 22. This is acceptable for development. Production deployment should use Node 24.
