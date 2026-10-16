# MB-667 combined screening meaning — draft content review

Status: **DRAFT — owner approved the draft.3 wording on 2026-10-16. Not clinically or domain reviewed and not approved for production deployment.**
The user requested twenty neutral variants for review and supplied the shared
screen flow in the MB-667 Stitch prototype. Draft.3 names both results with the
score-card terms (`dấu hiệu trầm cảm`, `lo âu`), states the highest-level action
in the visible meaning for professional pathways, and uses the rationale to
explain which result triggers the existing routing rule.

- Presentation policy: `mb-combined-screening-meaning-v1`.
- Draft content version: `mb-combined-screening-meaning-vi-vn-v1-draft.3`.
- Supported source policy: `mb-support-routing-capstone-v1`.
- Owner: Care. Source authority remains the saved exact compatible PHQ-9/GAD-7
  pair and the existing stored support tier.
- Reference period: 14 days, independently for each instrument.
- Existing routing: four Minimal/Mild pairs use self-guided support; all other
  legal pairs use professional support, before the independent safety override.
  These are existing pathway rules, not a new combined health classification.
- Read boundary: `GET /api/v1/support-evaluations/{supportEvaluationId}/combined-meaning`.
  The separate endpoint preserves the original v1 response and installed strict
  mobile consumers. V2 continues to expose two separate domain pathways with no
  top-level support tier.

## Shared layout and safety precedence

Twenty rows are content variants for one layout, not twenty separate screens.
Show both instrument levels separately, one short meaning, its rationale and the
existing approved next step. On independent positive PHQ-9 safety evidence,
`presentationPriority=SAFETY` and the original stored safety tier and approved
safety guidance take precedence over ordinary meaning/CTA. The base row remains
traceable; neither instrument band changes. Severe bands alone never introduce
a safety signal. An explicit user request for immediate support continues through
the existing separate safety flow and is not inferred from this projection.

The response labels the original evaluation ID, exact assessment references,
evaluation time and source policy separately from this new draft presentation
version. Reopening a known compatible historical record uses its original pair,
not current standalone history. This does not claim that the draft copy was
shown at the original evaluation time. Unsupported source policies, voided,
missing, incomplete or incompatible source evidence and inconsistent stored
tiers return `COMBINED_SCREENING_MEANING_UNAVAILABLE`; missing and foreign
evaluation identifiers share opaque `SUPPORT_EVALUATION_NOT_FOUND`.

No migration, historical snapshot rewrite, event, entitlement, Guide generation,
plan mutation, scoring change, AI routing or private answer disclosure is added.
Guide navigation must carry the same exact saved pair. Existing original
per-instrument meaning, next-step and disclaimer copy are not replaced.

## Copy to review

The table is an exact copy of the immutable draft catalogue in
`DraftCombinedScreeningMeaningCatalog`. A focused parameterized test compares
every row with the catalogue to prevent drift. Review wording together with the
Stitch prototype before publishing any branch, PR or deployed runtime.

| Base meaning code | PHQ-9 level | GAD-7 level | Draft meaning | Draft rationale | Base support pathway |
| --- | --- | --- | --- | --- | --- |
| PHQ9_MINIMAL_GAD7_MINIMAL | MINIMAL | MINIMAL | Dấu hiệu trầm cảm và lo âu đều rất ít. | Cả hai kết quả đều ở mức tối thiểu, nên duy trì những thói quen đang giúp bạn ổn định là bước phù hợp. Bạn có thể làm lại bài sàng lọc khi cảm nhận của mình thay đổi. | SELF_GUIDED_SUPPORT |
| PHQ9_MINIMAL_GAD7_MILD | MINIMAL | MILD | Lo âu ở mức nhẹ, dấu hiệu trầm cảm rất ít. | Cả hai kết quả đều chưa vượt mức nhẹ, nên bạn có thể bắt đầu bằng các hoạt động tự chăm sóc, như một bài thư giãn ngắn, và để ý những lúc lo âu xuất hiện. | SELF_GUIDED_SUPPORT |
| PHQ9_MINIMAL_GAD7_MODERATE | MINIMAL | MODERATE | Lo âu ở mức trung bình, dấu hiệu trầm cảm rất ít. Bạn nên trao đổi thêm với chuyên gia. | Lo âu từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia, để bạn hiểu rõ hơn điều đang diễn ra. Trong lúc chờ, các hoạt động tự chăm sóc nhẹ nhàng vẫn có thể giúp bạn. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MINIMAL_GAD7_SEVERE | MINIMAL | SEVERE | Lo âu ở mức nặng, dấu hiệu trầm cảm rất ít. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Kết quả lo âu nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Chuyên gia có thể cùng bạn tìm cách hỗ trợ phù hợp. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MILD_GAD7_MINIMAL | MILD | MINIMAL | Dấu hiệu trầm cảm ở mức nhẹ, lo âu rất ít. | Cả hai kết quả đều chưa vượt mức nhẹ, nên bạn có thể bắt đầu bằng nghỉ ngơi, ăn uống đều đặn và một hoạt động nhỏ giúp nâng đỡ tâm trạng. | SELF_GUIDED_SUPPORT |
| PHQ9_MILD_GAD7_MILD | MILD | MILD | Dấu hiệu trầm cảm và lo âu đều ở mức nhẹ. | Cả hai kết quả đều ở mức nhẹ, nên các hoạt động tự chăm sóc vừa sức là bước phù hợp để bắt đầu. Bạn có thể để ý điều gì giúp mình dễ chịu hơn và làm lại bài sàng lọc nếu tình trạng nặng thêm. | SELF_GUIDED_SUPPORT |
| PHQ9_MILD_GAD7_MODERATE | MILD | MODERATE | Lo âu ở mức trung bình, dấu hiệu trầm cảm ở mức nhẹ. Bạn nên trao đổi thêm với chuyên gia. | Lo âu từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia. Khi trao đổi, bạn có thể kể cả những thay đổi về tâm trạng để chuyên gia có bức tranh đầy đủ. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MILD_GAD7_SEVERE | MILD | SEVERE | Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức nhẹ. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Kết quả lo âu nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Bạn có thể kể cả những thay đổi về tâm trạng khi trao đổi. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MINIMAL | MODERATE | MINIMAL | Dấu hiệu trầm cảm ở mức trung bình, lo âu rất ít. Bạn nên trao đổi thêm với chuyên gia. | Dấu hiệu trầm cảm từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia, để bạn hiểu rõ hơn những thay đổi về tâm trạng, giấc ngủ hay năng lượng. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MILD | MODERATE | MILD | Dấu hiệu trầm cảm ở mức trung bình, lo âu ở mức nhẹ. Bạn nên trao đổi thêm với chuyên gia. | Dấu hiệu trầm cảm từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia. Khi trao đổi, bạn có thể kể cả những lúc lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MODERATE | MODERATE | MODERATE | Dấu hiệu trầm cảm và lo âu đều ở mức trung bình. Bạn nên trao đổi thêm với chuyên gia. | Cả hai kết quả đều từ mức trung bình trở lên, nên MentalBridge gợi ý trao đổi với chuyên gia về cả tâm trạng lẫn lo âu trong cùng một lần để không bỏ sót phần nào. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_SEVERE | MODERATE | SEVERE | Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức trung bình. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Kết quả lo âu nằm ở mức cao nhất của thang đo và dấu hiệu trầm cảm ở mức trung bình, nên việc trao đổi với chuyên gia nên được đặt lên trước. Hãy kể cả hai điều này khi trao đổi. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MINIMAL | MODERATELY_SEVERE | MINIMAL | Dấu hiệu trầm cảm ở mức khá nặng, lo âu rất ít. Bạn nên sớm trao đổi với chuyên gia. | Dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên diễn ra sớm thay vì chờ xem có tự cải thiện hay không. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MILD | MODERATELY_SEVERE | MILD | Dấu hiệu trầm cảm ở mức khá nặng, lo âu ở mức nhẹ. Bạn nên sớm trao đổi với chuyên gia. | Dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên diễn ra sớm. Khi trao đổi, bạn có thể kể cả những lúc lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MODERATE | MODERATELY_SEVERE | MODERATE | Dấu hiệu trầm cảm ở mức khá nặng, lo âu ở mức trung bình. Bạn nên sớm trao đổi với chuyên gia. | Dấu hiệu trầm cảm ở mức khá nặng và lo âu ở mức trung bình, nên việc trao đổi với chuyên gia nên diễn ra sớm và bao gồm cả tâm trạng lẫn lo âu. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_SEVERE | MODERATELY_SEVERE | SEVERE | Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức khá nặng. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Lo âu nằm ở mức cao nhất của thang đo và dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Mỗi kết quả cần được chuyên gia xem xét riêng. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MINIMAL | SEVERE | MINIMAL | Dấu hiệu trầm cảm ở mức nặng, lo âu rất ít. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MILD | SEVERE | MILD | Dấu hiệu trầm cảm ở mức nặng, lo âu ở mức nhẹ. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước. Bạn có thể kể cả những lúc lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MODERATE | SEVERE | MODERATE | Dấu hiệu trầm cảm ở mức nặng, lo âu ở mức trung bình. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo và lo âu ở mức trung bình, nên việc trao đổi với chuyên gia nên được đặt lên trước và bao gồm cả hai điều này. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_SEVERE | SEVERE | SEVERE | Dấu hiệu trầm cảm và lo âu đều ở mức nặng. Bạn nên ưu tiên trao đổi sớm với chuyên gia. | Cả hai kết quả đều nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Mỗi kết quả cần được chuyên gia xem xét riêng. | PROFESSIONAL_SUPPORT_RECOMMENDED |

## Presentation decisions for draft.3 (2026-10-16)

- The web result card keeps one static professional heading per highest band:
  `Nên sớm trao đổi với chuyên gia` when either result is Moderately severe or
  Severe, otherwise `Trao đổi với chuyên gia khi bạn sẵn sàng`. It reads only the
  exact saved levels and adds no new classification.
- The approved v1 next-step copy (`mb-support-next-step-vi-vn-v1`, including
  "nếu mong muốn") stays unchanged. It is versioned, shared by every v1
  consumer, and can change only through a new next-step content version and
  migration, which is outside MB-667. The draft.3 meaning carries the
  band-specific urgency.
- Severe rows add no crisis pointer. Bands alone never introduce a safety
  signal; independent PHQ-9 item-9 evidence keeps `presentationPriority=SAFETY`,
  and the global crisis-support entry remains available on every page.

## Remaining gates

- Owner approved the draft.3 copy variants on 2026-10-16. A draft version is
  not clinical or production approval; domain review remains required.
- Frontend implementation remains traceable to the owner-supplied Stitch
  prototype while preserving the existing authenticated shell.
- Focused tests prove catalogue coverage, source ownership, safety precedence,
  stable read-only behavior and old response compatibility.
- No deployment into the shared appointment demo stack while these gates remain.
