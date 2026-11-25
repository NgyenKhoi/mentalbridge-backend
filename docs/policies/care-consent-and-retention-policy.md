# Care consent and assessment retention policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-PRIVACY-CARE-001` |
| Policy version | `1.2-capstone` |
| Status | `PRODUCT OWNER APPROVED — CONTROLLED CAPSTONE ONLY` |
| Effective date | 2026-11-06 for MB-806 AI-purpose amendment; existing controlled local/test/demo scope |
| Capstone decision | MB-178 consent, disclosure, anonymous lifetime, and bounded retention decisions approved on 2026-09-02; Story 1102 instrument-neutral `privacy-capstone-v3` text approved by the Product Owner on 2026-09-10 |
| Owning service | Care Service |
| Applies to | Anonymous and registered Care assessment flows in Vietnam |

## Separate consent and disclosure paths

### Current AI-purpose amendment (MB-806)

[ADR 0050 / MB-TWO-LEVEL-AI-CONSENT-001](../adr/0050-two-level-ai-consent.md)
is the current prospective authority. Immutable historical disclosures and
decisions retain their exact text and versions.

MentalBridge must not combine these purposes into one broad toggle:

| Purpose | Current type/version and treatment |
| --- | --- |
| Deterministic assessment processing | `PRIVACY_POLICY` / `privacy-capstone-v3`; independent processing gate, never AI permission |
| L1 — AI trò chuyện | `AI_PROCESSING` / `ai-processing-capstone-v2`; actively sent content and bounded eligible same-conversation memory only; current existing v2 grants remain valid without reconsent |
| L2 — AI dùng bối cảnh | `AI_CONTEXT_PROCESSING` / `ai-context-processing-capstone-v1`; new independent grant for bounded approved personal context, effective only with current granted L1; no backfill |
| Voice microphone/processing | Separate `AI_VOICE_PROCESSING` / `ai-voice-processing-capstone-v2`, plus effective L1; L2 cannot replace it or broaden Voice sources |
| Specialist sharing | Separate revocable subject/specialist/appointment/snapshot/purpose/window grant |
| Research use | Deferred; `RESEARCH_DATA` remains unavailable |
| Marketing notification | Deferred; `MARKETING_NOTIFICATION` remains unavailable |

Care owns immutable Vietnamese notices, append-only subject-scoped decisions and
the atomic effective L1/L2 snapshot. Raw granted decisions are not effective
authorization. L1 never permits personal-record retrieval. L2 covers owned,
minimized, explicitly eligible Journal/context sources, exact and longitudinal
analysis, Care AI-evidence reuse, plan companion, saved-brief AI draft, Guide AI
phrasing and emotion AI projection. Consent cannot grant source access, raw
assessment answers, unlimited records, specialist sharing, research or marketing.
Neither checkbox is preselected.

A new L2 grant without effective L1 returns `409 AI_PROCESSING_CONSENT_REQUIRED`
without inserting a decision. L2 withdrawal is always accepted. L1 withdrawal
suspends current true L2 (`DEPENDENCY_REQUIRED`) without fabricating withdrawal;
explicit L1 regrant restores its effectiveness and selects no new sources. Each
level's own missing, revoked or outdated reason takes precedence over dependency.
Both streams share the existing profile lock and strictly increasing microsecond
decision order. Exact idempotent replay precedes new-policy/dependency validation,
returns the original decision and never restores authority.

Journal/AI checks current effective L2 before each personal source
query/decryption/projection and again immediately before each provider attempt,
including retries, redispatch and reclaimed jobs. Chat checks effective L1 at
request and final attempt; known L2-off skips personal assembly and filters whole
personal-kind memory pairs before decryption. Loss during assembly discards
personal input and rebuilds plain input under the final successful L1 read.
Dependency uncertainty fails closed, rather than treating it as an L2 refusal.
The verified end-user bearer remains ephemeral and unlogged; no consent cache,
shadow grant, saved prompt or raw provider output is introduced.

All current context kinds (`JOURNAL`, `SUPPORT_PLAN`, `REASSESSMENT`, `RESOURCE`)
are personal. New labels preserve the union of actual fetched and retained-memory
kinds; old messages/results remain immutable. New conversations select no source;
saved selections are preferences and resume only with L2 and fresh source checks.
Clearing sources remains allowed. Deterministic screening, safety, Guide and plan
fallbacks require neither AI level. Withdrawal prevents new work without deleting
history; deletion remains a separate workflow. Existing source bounds, retention,
encryption, quotas, public-catalogue suggestions and Care decision authority stay.

### Current Voice consent boundary

The implemented Voice v2 boundary follows
[ADR 0046](../adr/0046-ai-voice-companion-contract.md) as amended by
[ADR 0048](../adr/0048-opt-in-text-voice-context.md). Voice requires L1 and its
separate immutable `ai-voice-processing-capstone-v2` notice, entitlement/quota and
fresh owner/session authority at admission, reconnect, renewal, every generation
and publication. The explicitly selected Text handoff remains bounded to eligible
empty-kind pairs; L2 unlocks no further Voice source. Withdrawal follows existing
current-call cutoff and RAM purge. Voice v1 is historical; the v2 notice and
reacceptance rules are unchanged. Voice metadata's separate 30-day bound does not
rewrite assessment, text-history or Care decision retention.

### Immutable L2 disclosure text

`ai-context-processing-capstone-v1` is current and publishes exactly:

> **AI dùng bối cảnh**
>
> MentalBridge dùng AI để xử lý các nguồn dữ liệu cá nhân đã được cho phép và bạn chọn, gồm nhật ký, thông tin sàng lọc tối giản, kế hoạch hỗ trợ và tiến độ tài nguyên, để phân tích nhật ký, trò chuyện có bối cảnh, hỗ trợ kế hoạch, gợi ý bản tóm tắt trước buổi tư vấn và diễn đạt Hướng dẫn hỗ trợ. Mức đồng ý này chỉ có hiệu lực khi bạn đang đồng ý AI trò chuyện. Bạn có thể rút lại từng mức đồng ý để chặn việc xử lý mới; rút lại AI trò chuyện sẽ tạm dừng AI dùng bối cảnh, và mức bối cảnh hiện tại có hiệu lực trở lại khi bạn đồng ý AI trò chuyện lại.

### Coordinated rollout

Deploy Care's additive L2 stream/snapshot before Journal/AI's new checks. Coordinate
cutover of every existing broad AI consumer: the valid L1 endpoint cannot prevent
old consumers from incorrectly treating L1 as L2. Nobody receives L2 until an
explicit new grant. MB-805/807 frontend consumers synchronize the merged contract
and render backend notices/current authority; existing grants establish L1 only.

### Immutable AI-processing disclosure text

`ai-processing-capstone-v2` stays current for L1 under ADR 0050. Its immutable
original notice described explicit journal analysis and an editable suggestion from
one saved private ConsultationBrief plus minimized PHQ-9/GAD-7 levels. The
ConsultationBrief purpose excludes raw Journal content, screening answers, chat,
diagnoses, private notes, automatic saving, approval, sharing, access grants, and
appointment changes. The backend publishes this Vietnamese title and content:

> **Đồng ý xử lý dữ liệu bằng AI**
>
> MentalBridge chỉ dùng AI khi bạn chủ động yêu cầu. Phạm vi bao gồm nội dung nhật ký cụ thể bạn chọn, các phiên bản nhật ký trong khoảng thời gian giới hạn, hoặc bản tóm tắt trước buổi tư vấn đã lưu cùng mức sàng lọc tối giản. AI chỉ tạo gợi ý để bạn xem lại và chỉnh sửa; không chẩn đoán, không chấm PHQ-9/GAD-7, không quyết định an toàn, quyền lợi hay kế hoạch hỗ trợ, và không tự phê duyệt hoặc chia sẻ với chuyên gia. Nội dung nhật ký, câu trả lời sàng lọc, chat và ghi chú riêng không được dùng cho gợi ý tóm tắt này. Bạn có thể rút lại đồng ý để chặn các yêu cầu hoặc lần thử lại mới.

`ai-processing-capstone-v1` remains an immutable historical decision value for
exact-revision and bounded longitudinal journal analysis and published this title
and content:

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
- [x] Product Owner approved backend ownership of immutable versioned disclosure text; `privacy-capstone-v3` is current for PHQ-9/GAD-7 while v1/v2 remain historical, and MB-370 introduced immutable `ai-processing-capstone-v2`; MB-806 narrows that current grant to L1 and adds the independent L2 notice for personal context. Specialist sharing remains a separate explicit grant; research and marketing remain deferred.
- [x] Product Owner approved a 30-minute sliding inactivity deadline, two-hour absolute lifetime, and persisted idempotent completion semantics.
- [x] Product Owner approved registered history only for controlled synthetic/test/demo use without a production retention claim.
- [x] Product Owner approved the appointment-scoped `SPECIALIST_SHARING` grant and user-approved `ConsultationBrief` boundary under ADR 0014.
- [ ] Specialist grant contract, concurrency-safe authorization, audit behavior, runtime UI and security/privacy review pass before specialist reads are enabled.
- [ ] Security and legal/privacy reviewers approve a future public real-user production policy.
