---
name: "Implementation card"
about: "Định nghĩa một vertical slice trước khi bắt đầu code"
title: "feat(<scope>): <lowercase short description>"
labels: ["enhancement"]
assignees: ""
---

## Kết quả cần đạt

Mô tả hành vi quan sát được và lý do sản phẩm cần hành vi này.

## Source of truth

- Owner service:
- Product requirement/use case:
- Active contract hoặc proposal:
- ADR/domain/safety rule:
- Migration và field dictionary liên quan:

## Phạm vi triển khai

- Thư mục được phép sửa:
- Caller/consumer bị ảnh hưởng:
- Ngoài phạm vi:
- Quyết định còn mở cần owner duyệt:

## Acceptance examples

- [ ] Happy path:
- [ ] Validation/authorization:
- [ ] Conflict/concurrency/idempotency:
- [ ] Timeout/retry/dependency failure:
- [ ] Privacy/logging/deletion nếu áp dụng:

## Required commands

```text
pwsh ./scripts/verify-repository.ps1
<module format/lint/typecheck/test/contract/migration/build commands>
```

## Delivery

- Priority:
- Estimate:
- Reviewer:
- Dependency/blocker:

Không tự điền issue number, assignee, estimate hoặc approval chưa tồn tại. Không bắt đầu code khi ownership, contract hay safety behavior còn mâu thuẫn.
