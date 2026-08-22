---
name: "Bug report"
about: "Báo lỗi có thể tái hiện và có phạm vi regression rõ"
title: "[BUG] <scope>: <short description>"
labels: ["bug"]
assignees: ""
---

## Hiện tượng

Mô tả hành vi thực tế, error code và correlation ID an toàn nếu có. Không dán token, journal/chat, assessment answer, payment payload hoặc dữ liệu nhạy cảm.

## Cách tái hiện

1.
2.
3.

## Kết quả mong đợi

Mô tả hành vi theo contract/domain rule hiện hành.

## Phạm vi và source of truth

- Owner service:
- Contract/ADR/domain rule:
- Commit/deployment đầu tiên bị ảnh hưởng:
- Caller/consumer liên quan:

## Môi trường

- OS/runtime:
- Module/commit:
- Tần suất tái hiện:
- Dependency state:

## Regression coverage

- [ ] Test tái hiện lỗi trước khi sửa.
- [ ] Happy path và error path liên quan.
- [ ] Authorization/validation.
- [ ] Consistency, retry, timeout hoặc dependency failure.
- [ ] Contract/migration compatibility nếu áp dụng.
