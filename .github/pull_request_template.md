## Tóm tắt thay đổi

- Loại thay đổi: `feat` | `fix` | `refactor` | `chore` | `docs` | `test`
- Module/owner:
- Hành vi hoặc vấn đề được giải quyết:
- Lý do cần thay đổi:

## Implementation contract

- Issue/TODO liên quan: `Closes #...` | `Refs #...` | `Không có issue`
- Contract active/proposal:
- ADR/domain rule liên quan:
- Migration và data dictionary:
- Thư mục được phép thay đổi:
- Ngoài phạm vi:
- Ví dụ acceptance/edge case:

## Ảnh hưởng và tương thích

- REST/Kafka/WebSocket compatibility:
- Data/rollback hoặc forward-migration plan:
- Authorization, privacy và dữ liệu nhạy cảm:
- Configuration/operations:
- Caller/consumer đã kiểm tra:

## Verification

| Command | Environment | Result |
| --- | --- | --- |
| `...` | local/CI | pass/fail và số test |

Nêu rõ command không chạy được, lỗi đầu tiên, nguyên nhân và ảnh hưởng. Không đánh dấu hoàn tất khi gate bắt buộc đang fail.

## Đồng bộ base

- [ ] Đã `git fetch origin` và kiểm tra divergence với `origin/dev` trước khi code.
- [ ] Đã fetch/đồng bộ lại ngay trước commit/push/PR.
- [ ] Đã review toàn bộ `origin/dev...HEAD` và không đưa file ngoài phạm vi vào PR.

## Checklist trước merge

- [ ] Tiêu đề theo Conventional Commits: `type(scope): lowercase short description` và thêm `(#issue)` chỉ khi có issue thật.
- [ ] Owner, source of truth, contract status và out-of-scope đã rõ.
- [ ] Controller/gateway thay đổi cùng OpenAPI và provider boundary tests.
- [ ] Event/WebSocket thay đổi cùng versioned schema và producer/consumer tests.
- [ ] Migration thay đổi cùng data dictionary và constraint/mapping tests.
- [ ] Configuration thay đổi cùng `.env.example`, README và configuration tests khi semantics thay đổi.
- [ ] Đã kiểm tra validation, authorization, conflict/concurrency, duplicate/retry và dependency failure phù hợp phạm vi.
- [ ] Không log/event/error token, password, raw journal/chat, assessment answer, private URL hoặc provider payload nhạy cảm.
- [ ] Không thêm layer/interface/shared abstraction nếu chưa có boundary hoặc hành vi cụ thể.
- [ ] Required GitHub quality gate đã pass.
