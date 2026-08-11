---
name: "📝 Triển khai TODO (Chuẩn Conventional)"
about: "Tạo Issue từ TODO trong README.md với tiêu đề chuẩn Commit Message Convention."
title: "feat(<scope>): <mô tả ngắn gọn bằng chữ thường>"
labels: ["enhancement", "todo"]
assignees: ""
---

## 1. Mô tả tính năng

> Tóm tắt ngắn gọn về tính năng cần triển khai dựa trên TODO trong `README.md`.
> *Ví dụ tiêu đề chuẩn: `feat(auth): implement google oauth login`*

* **Loại tác vụ (Commit Type):** `feat` (Tính năng mới) | `refactor` (Tối ưu code) | `chore` (Cập nhật lặt vặt) | `test` (Kiểm thử) | `docs` (Tài liệu) | `fix` (Sửa lỗi)
* **Phạm vi ảnh hưởng (Scope):** *Ví dụ: auth, database, ui, api...*
* **TODO gốc trong README.md:** > *Dán dòng TODO hoặc chèn link dẫn thẳng tới dòng TODO trong file README.md vào đây.*

---

## 2. Yêu cầu chi tiết

> Mô tả chi tiết các luồng xử lý, logic nghiệp vụ hoặc giao diện cần đạt được.

* **Mô tả logic:**
  - [ ] Luồng xử lý chính: ...
  - [ ] Các trường hợp ngoại lệ (Edge cases): ...
* **Các file/module dự kiến chỉnh sửa:** `src/...`

---

## 3. Tiêu chí hoàn thành (Definition of Done)

> Các điều kiện bắt buộc phải thỏa mãn để đóng Issue này.

- [ ] Tính năng hoạt động đúng yêu cầu kỹ thuật.
- [ ] **Đã xóa hoặc cập nhật dòng TODO tương ứng trong file `README.md`.**
- [ ] Đã viết Unit Test / Integration Test bổ sung.
- [ ] Pull Request giải quyết Issue này phải đặt tên theo chuẩn: `feat(scope): mô tả ngắn gọn (#số_issue)`

---

## 4. Thông tin quản lý

> Phần dành cho Project Manager / Tech Lead để điều phối.

* **Độ ưu tiên (Priority):** 🔴 High | 🟡 Medium | 🔵 Low
* **Ước lượng thời gian (Estimation):** ___ Story Points / Giờ
* **Người kiểm thử / Reviewer:** @