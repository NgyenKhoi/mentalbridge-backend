# Care consent and assessment retention policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-PRIVACY-CARE-001` |
| Policy version | `1.1-capstone` |
| Status | `PRODUCT OWNER APPROVED — CONTROLLED CAPSTONE ONLY` |
| Effective date | 2026-09-10 for controlled local/test/demo use |
| Capstone decision | MB-178 consent, disclosure, anonymous lifetime, and bounded retention decisions approved on 2026-09-02; Story 1102 instrument-neutral `privacy-capstone-v3` text approved by the Product Owner on 2026-09-10 |
| Owning service | Care Service |
| Applies to | Anonymous and registered Care assessment flows in Vietnam |

## Separate consent and disclosure paths

MentalBridge must not combine these purposes into one broad toggle:

| Purpose | Required treatment |
| --- | --- |
| Deterministic assessment processing | The user must view and grant the backend-owned `privacy-capstone-v3` consent; this is a processing gate, not a clinical-eligibility rule, and no AI processing is implied |
| AI processing | Backend consent type `AI_PROCESSING` at `ai-processing-capstone-v1`; covers only explicit exact-source single-entry and bounded longitudinal journal analysis; user-facing consent UI remains a Story 6203/follow-up dependency |
| Specialist sharing | Product semantics approved by `MB-CONSULTATION-FLOW-001`; separate revocable grant scopes subject, specialist, appointment, approved snapshot, purpose, and time window |
| Research use | Deferred; `RESEARCH_DATA` is not exposed and production data is excluded by default |
| Marketing notification | Deferred; `MARKETING_NOTIFICATION` is not exposed until a corresponding feature exists |

Care accepts and exposes `PRIVACY_POLICY` and `AI_PROCESSING` as independent append-only decision streams. Care publishes each exact Vietnamese disclosure and version; clients must not maintain independent copies. `AI_PROCESSING` does not authorize research, marketing, specialist sharing, or safety decisions. A registered withdrawal takes effect for new processing immediately but neither deletes historical assessments or analysis results nor rewrites audit evidence. Deletion is a separate workflow. `RESEARCH_DATA` and `MARKETING_NOTIFICATION` remain reserved and unavailable.

Journal/AI checks the current `AI_PROCESSING` decision through the narrow Care
REST authorization endpoint when accepting an analysis request and again
immediately before every provider attempt. It forwards the already verified
end-user bearer JWT received through the gateway. The token is not persisted in
the analysis job or logged. Care unavailability, an expired token, a missing
decision, or a withdrawal all fail closed. Revocation prevents a new provider
attempt and retry but does not erase a previously normalized result.

### Immutable AI-processing disclosure text

`ai-processing-capstone-v1` is current for new exact-revision and bounded
longitudinal journal analysis and publishes this Vietnamese title and content:

> **Đồng ý xử lý nhật ký bằng AI**
>
> MentalBridge chỉ xử lý bằng AI một phiên bản nhật ký cụ thể khi bạn chủ động yêu cầu, hoặc các phiên bản nhật ký cụ thể trong hai khoảng thời gian giới hạn khi bạn chủ động yêu cầu phân tích theo thời gian. Kết quả chỉ hỗ trợ phản ánh và điều hướng, không phải chẩn đoán, không chấm PHQ-9/GAD-7, không quyết định safety, eligibility hay thay đổi SupportPlan. Sự đồng ý này không bao gồm nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Bạn có thể rút lại sự đồng ý để chặn các lần xử lý hoặc retry mới. Việc rút lại không tự động xóa kết quả đã lưu; xóa dữ liệu là một quy trình riêng.

The current controlled-Capstone disclosure version is `privacy-capstone-v3`. Versions `privacy-capstone-v1` and `privacy-capstone-v2` remain immutable historical decision values and are never backfilled onto or away from historical assessments. Version 3 covers deterministic PHQ-9 and GAD-7 processing, distinguishes authenticated history from session-scoped anonymous processing, and uses explicit consent and withdrawal terminology. Public real-user deployment still requires a separately reviewed production privacy, retention, security, and legal policy.

### Immutable disclosure text

`privacy-capstone-v2` retains this exact historical title and content:

> **Thông báo về việc xử lý dữ liệu sức khỏe**
>
> MentalBridge lưu thông tin hồ sơ, câu trả lời PHQ-9 và kết quả sàng lọc được tính từ câu trả lời của bạn để hiển thị lịch sử và hỗ trợ bạn thực hiện lại bài sàng lọc. Kết quả sàng lọc chỉ mang tính tham khảo, không phải chẩn đoán y khoa và không thay thế tư vấn của chuyên gia. Việc xác nhận thông báo này chỉ áp dụng cho quá trình xử lý bài sàng lọc; không bao gồm xử lý bằng AI, nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Bạn có thể rút lại xác nhận để ngăn các lần xử lý mới. Việc rút lại xác nhận không tự động xóa lịch sử đã lưu; yêu cầu xóa dữ liệu là một quy trình riêng.

`privacy-capstone-v3` is current for new PHQ-9 and GAD-7 submissions and uses this approved title and content:

> **Thông báo và đồng ý xử lý dữ liệu sàng lọc**
>
> MentalBridge xử lý các câu trả lời PHQ-9 hoặc GAD-7 và kết quả sàng lọc được tính từ các câu trả lời đó nhằm cung cấp chức năng sàng lọc sức khỏe tâm lý.
>
> Đối với người dùng đã đăng nhập, MentalBridge có thể lưu kết quả sàng lọc cùng thông tin cần thiết của tài khoản để hiển thị lịch sử và hỗ trợ bạn thực hiện lại bài sàng lọc.
>
> Đối với phiên ẩn danh, dữ liệu chỉ được xử lý trong phạm vi của phiên ẩn danh theo chính sách hiện hành và không tự động được gắn vào tài khoản được tạo sau đó.
>
> Kết quả PHQ-9 và GAD-7 chỉ mang tính sàng lọc, không phải chẩn đoán y khoa và không thay thế đánh giá hoặc tư vấn của chuyên gia.
>
> Sự đồng ý này chỉ áp dụng cho việc xử lý dữ liệu cần thiết để thực hiện và lưu kết quả bài sàng lọc. Sự đồng ý này không bao gồm xử lý dữ liệu bằng AI, sử dụng dữ liệu cho nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Các mục đích đó, nếu được triển khai, phải có quyết định đồng ý riêng.
>
> Bạn có thể rút lại sự đồng ý đối với các hoạt động xử lý mới trong tương lai. Việc rút lại sự đồng ý không tự động xóa dữ liệu đã được lưu trước đó; yêu cầu xóa dữ liệu là một quy trình riêng theo chính sách hiện hành.

## MB-179 specialist-sharing boundary

The Capstone permits definition and contract planning but does not yet authorize runtime specialist access. Specialist handoff is registered-user-only, voluntary and user-initiated. Under ADR 0014 it requires a separate revocable `SPECIALIST_SHARING` grant naming the subject, specialist, appointment, purpose, approved `ConsultationBrief` snapshot, expiry and grant version. The default appointment-scoped window is `startsAt - 24h` through `startsAt + 24h`; another appointment requires a new brief and grant.

The approved snapshot may contain bounded screening, SupportEvaluation, active SupportPlan, trends, user goals/preferences/barriers, consented journal-derived context, and a previous user-approved `SessionSummary`, with exact source-version references. Raw answers, raw journals, full AI-analysis history and unrelated data are excluded. An appointment or paid plan never creates consent. Every read re-checks the current grant in the data owner and records minimized audit evidence.

This boundary is definition-complete for MB-179. Runtime sharing remains unavailable until the grant contract, concurrency-safe authorization, audit behavior and required security/privacy review pass. Public real-user collection and sharing remain production-blocked; Sprint 2 validation uses synthetic/test data.

## Anonymous assessment

Required behavior:

- token is high entropy, returned once, and stored only as a hash;
- session and result are ephemeral;
- no longitudinal history;
- no specialist access;
- no AI personalization requiring a persistent profile;
- no silent attachment to a later registered account;
- expiry and cleanup are enforced by Care and cannot depend on the client clock.

Approved controlled-Capstone values:

```text
anonymous inactivity TTL = 30 minutes
anonymous maximum absolute lifetime = 2 hours
```

Valid authenticated activity extends the inactivity deadline up to, but never beyond, two hours after session creation. A new request at or after the effective deadline is rejected. An idempotent operation accepted before expiry may complete from its authoritative persisted state; expiry does not authorize a new operation or a later read. Cleanup/deletion evidence remains an operational production decision and does not permit access after expiry.

## Registered assessment retention and deletion

Sprint 2 registered history is approved only for synthetic/test/demo data. It deliberately makes no production claim about:

- maximum retention duration or user-controlled history duration;
- immediate versus grace-period deletion behavior;
- whether a voided result has a distinct audit retention period;
- treatment of minimized outbox/audit evidence after content deletion;
- backup expiry and restore behavior;
- legal basis and version owner;
- export format and deadline.

The existing null registered-retention deadline means only that the controlled demo keeps its synthetic history for reassessment. It is not an approved indefinite production-retention rule. A future production policy and migration must define retention, deletion, backup, audit-minimization, and export behavior before real-user collection is enabled.

## Approval blockers

- [x] Product Owner approved synthetic/test-data-only Sprint 2 validation and the non-executable MB-179 sharing boundary.
- [x] Product Owner approved the versioned assessment-processing disclosure and its separation from clinical eligibility.
- [x] Product Owner approved backend ownership of immutable versioned disclosure text; `privacy-capstone-v3` is current for PHQ-9/GAD-7 while v1/v2 remain historical, and `ai-processing-capstone-v1` is current for exact-revision/bounded-longitudinal AI processing. AI consent UI and specialist-sharing runtime remain follow-up work; research and marketing remain deferred.
- [x] Product Owner approved a 30-minute sliding inactivity deadline, two-hour absolute lifetime, and persisted idempotent completion semantics.
- [x] Product Owner approved registered history only for controlled synthetic/test/demo use without a production retention claim.
- [x] Product Owner approved the appointment-scoped `SPECIALIST_SHARING` grant and user-approved `ConsultationBrief` boundary under ADR 0014.
- [ ] Specialist grant contract, concurrency-safe authorization, audit behavior, runtime UI and security/privacy review pass before specialist reads are enabled.
- [ ] Security and legal/privacy reviewers approve a future public real-user production policy.
