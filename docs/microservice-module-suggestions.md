# Kiến trúc module microservices đã chốt

## 1. Mục tiêu

MentalBridge nên bắt đầu với một số ít microservice theo **bounded context**, không tách một service cho từng chức năng nhỏ. Mỗi service sở hữu nghiệp vụ và dữ liệu của mình; các service khác chỉ truy cập qua API hoặc domain event, không đọc trực tiếp bảng/collection của nhau.

Ngôn ngữ chính:

- **Java + Spring Boot** cho `identity-service`, `care-service` và `consultation-service`, nơi cần tính nhất quán giao dịch, bảo mật, phân quyền và audit.
- **TypeScript + NestJS** cho `journal-ai-service`, `realtime-service` và `content-notification-service`, nơi có nhiều I/O, Kafka consumer, provider integration và kết nối WebSocket.
- **Python** chỉ cho `phobert-worker`; worker không sở hữu nghiệp vụ hoặc dữ liệu nguồn.
- **REST + JSON DTO** là giao tiếp đồng bộ giữa các microservice. **Kafka** dùng cho task/event bất đồng bộ. **Redis** chỉ dùng cho trạng thái realtime/caching ngắn hạn và WebSocket fan-out.

## 2. Các microservice được đề xuất

| Service / module | Ngôn ngữ và framework chính | Trách nhiệm | Dữ liệu sở hữu | Giao tiếp chính |
| --- | --- | --- | --- | --- |
| `identity-service` | Java, Spring Boot | Đăng ký, đăng nhập, vai trò, xác minh email, reset mật khẩu, refresh token, trạng thái tài khoản, điều phối xóa tài khoản, audit/security projection tối thiểu | PostgreSQL schema `identity` và projection an toàn | REST; Kafka account/deletion/audit events |
| `care-service` | Java, Spring Boot | Hồ sơ người dùng, consent, PHQ-9/GAD-7, chấm điểm, risk policy, intervention và follow-up | PostgreSQL schema `care` | REST; transactional outbox/events |
| `consultation-service` | Java, Spring Boot | Hồ sơ/xét duyệt/tìm kiếm/matching chuyên gia, lịch rảnh, cuộc hẹn, quyền truy cập theo consent, đánh giá | PostgreSQL schema `consultation`; object storage cho giấy tờ | REST; Kafka appointment/review/moderation events |
| `journal-ai-service` | TypeScript, NestJS | CRUD nhật ký, phiên bản nội dung, kiểm tra AI consent, điều phối job và chuẩn hóa kết quả LLM | MongoDB cho nhật ký/kết quả; PostgreSQL schema `ai` cho job/outbox | REST/JSON; Kafka; API nhà cung cấp AI |
| `realtime-service` | TypeScript, NestJS | REST lịch sử chat, WebSocket authorization/chat/presence/receipt/notification delivery | MongoDB cho chat; Redis cho presence, room và cross-instance fan-out | REST/JSON; WebSocket client; Kafka |
| `content-notification-service` | TypeScript, NestJS | Nội dung tự hỗ trợ, hotline, template, preference, lưu và điều phối notification | PostgreSQL schema `content` | REST/JSON; Kafka; provider push/email |
| `phobert-worker` | Python | Chạy inference PhoBERT theo job, validate và trả kết quả có cấu trúc | Không sở hữu dữ liệu nguồn | Kafka command/result |

## 3. Cách nhóm service cho MVP

Kiến trúc chốt sáu business microservice và một worker: ba Spring Boot, ba NestJS, một Python worker như bảng trên. Edge gateway/reverse proxy là hạ tầng, không phải business service và không chứa orchestration. Governance/reporting ban đầu là admin API và Kafka projection nằm trong owner phù hợp; chỉ tách thêm deployable bằng ADR khi có lý do scale, release, data ownership hoặc security đo được.

## 4. Cấu trúc source code gợi ý

```text
mentalbridge-backend/
├── services/
│   ├── identity-service/            # Spring Boot
│   ├── care-service/                # Spring Boot
│   ├── consultation-service/        # Spring Boot
│   ├── journal-ai-service/          # NestJS + TypeScript
│   ├── realtime-service/            # NestJS + TypeScript
│   └── content-notification-service/# NestJS + TypeScript
├── workers/
│   └── phobert-worker/              # Python
├── contracts/
│   ├── openapi/                     # REST contracts
│   ├── events/                      # Kafka JSON Schema/AsyncAPI
│   └── websocket/                   # WebSocket JSON message contracts
├── database/
├── deploy/
│   └── docker-compose.yml
└── docs/
```

Trong mỗi Spring service, chia package theo feature/domain thay vì gom toàn bộ controller, service và repository ở các package dùng chung:

```text
com.mentalbridge.care
├── assessment/
├── consent/
├── risk/
├── intervention/
├── followup/
└── shared/       # chỉ chứa hạ tầng dùng nội bộ service
```

Trong Node.js realtime service:

```text
src/
├── auth/
├── conversations/
├── messages/
├── presence/
├── receipts/
└── infrastructure/
```

## 5. Quy tắc ranh giới module

- Mỗi service có database schema/database user riêng; không dùng chung JPA entity, repository hoặc truy vấn chéo schema.
- Không tạo một thư viện `common-domain` chứa model nghiệp vụ dùng chung. Chỉ chia sẻ các thành phần kỹ thuật ổn định như correlation ID, observability và event envelope.
- REST/JSON DTO dùng cho yêu cầu cần phản hồi ngay và mọi query giữa service: đăng nhập, chấm điểm assessment, xem consent hiện tại và đặt lịch.
- WebSocket chỉ nối client với `realtime-service`. Các service khác không query hoặc gọi nhau qua WebSocket.
- Kafka dùng cho AI analysis, notification, reporting, audit và account deletion. Producer PostgreSQL dùng transactional outbox; consumer xử lý idempotent theo `messageId` và commit offset sau side effect.
- Redis giữ presence TTL, connection/room routing, cache/rate limit ngắn hạn và fan-out WebSocket giữa replica; không giữ business truth.
- Event không chứa access token, nội dung nhật ký thô hoặc nội dung chat. Chỉ gửi định danh và dữ liệu tối thiểu cần thiết.
- API Gateway không chứa business logic và không trở thành nơi gọi nối tiếp nhiều service để xử lý nghiệp vụ.

## 6. Lý do chọn Spring và Node.js

### Spring Boot

Phù hợp với Identity, Care và Consultation vì các module này có nhiều quy tắc nghiệp vụ, transaction PostgreSQL, validation, RBAC, audit và yêu cầu test nhất quán. Spring Security, Spring Data, Flyway và Resilience4j tạo nền tảng thống nhất cho ba service này.

### Node.js + TypeScript

NestJS được chốt cho Journal/AI, Realtime và Content/Notification. Các module này chủ yếu điều phối I/O: MongoDB, Kafka, Redis, WebSocket, LLM, email và push provider. TypeScript strict và runtime validation giữ DTO/event contract nhất quán. NestJS không tự quyết định consent, risk hoặc trạng thái appointment; nó gọi REST tới owner khi cần dữ liệu hiện thời hoặc dùng projection chỉ khi nghiệp vụ chấp nhận eventual consistency.

## 7. Thứ tự triển khai

| Giai đoạn | Service ưu tiên | Kết quả |
| --- | --- | --- |
| Iteration 1 | Gateway, Identity, Care | Auth, profile/consent, PHQ-9/GAD-7 |
| Iteration 2 | Journal/AI, Care | Nhật ký, phân tích bất đồng bộ, risk/intervention |
| Iteration 3 | Consultation, Realtime, Notification | Chuyên gia, đặt lịch, chat, reminder |
| Iteration 4 | Governance/Reporting | Moderation, audit search, deletion, báo cáo và benchmark |

Quyết định tách thêm service phải dựa trên ít nhất một lý do đo được: cần scale độc lập, release độc lập, data ownership khác biệt, yêu cầu bảo mật riêng hoặc một nhóm khác chịu trách nhiệm vận hành.
