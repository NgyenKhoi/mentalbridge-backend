# MB-667 combined screening meaning — draft content review

Status: **DRAFT — pending owner content review. Not Care-reviewed or approved for deployment.**
The user requested twenty neutral variants for review and supplied the shared
screen flow in the MB-667 Stitch prototype. This catalogue remains a content
draft even though implementation and design verification can proceed locally.

- Presentation policy: `mb-combined-screening-meaning-v1`.
- Draft content version: `mb-combined-screening-meaning-vi-vn-v1-draft.2`.
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
| PHQ9_MINIMAL_GAD7_MINIMAL | MINIMAL | MINIMAL | Hai nhóm dấu hiệu được hỏi đến đều xuất hiện rất ít trong lần sàng lọc này. | Bạn có thể duy trì nhịp nghỉ ngơi, ăn uống và vận động; đồng thời thử các tài nguyên tự hỗ trợ phù hợp. | SELF_GUIDED_SUPPORT |
| PHQ9_MINIMAL_GAD7_MILD | MINIMAL | MILD | Các dấu hiệu lo âu ở mức nhẹ, còn nhóm PHQ-9 được ghi nhận rất ít. | Bạn có thể bắt đầu với một hoạt động thư giãn ngắn và quan sát những lúc cảm giác lo âu xuất hiện. | SELF_GUIDED_SUPPORT |
| PHQ9_MINIMAL_GAD7_MODERATE | MINIMAL | MODERATE | Kết quả lo âu cần được chú ý, dù PHQ-9 ghi nhận rất ít dấu hiệu. | Lo âu là nội dung nên ưu tiên trao đổi với chuyên gia; bạn vẫn có thể thử một hoạt động tự hỗ trợ nhẹ nhàng. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MINIMAL_GAD7_SEVERE | MINIMAL | SEVERE | Dấu hiệu lo âu ở mức nặng, trong khi PHQ-9 ghi nhận rất ít dấu hiệu. | Kết quả lo âu gợi ý ưu tiên trao đổi với chuyên gia để nhận hỗ trợ phù hợp. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MILD_GAD7_MINIMAL | MILD | MINIMAL | Nhóm dấu hiệu PHQ-9 ở mức nhẹ, còn lo âu được ghi nhận rất ít. | Bạn có thể bắt đầu bằng nghỉ ngơi, ăn uống đều đặn và một hoạt động nhỏ giúp nâng đỡ tâm trạng. | SELF_GUIDED_SUPPORT |
| PHQ9_MILD_GAD7_MILD | MILD | MILD | Cả hai nhóm dấu hiệu đều ở mức nhẹ, có thể bắt đầu bằng những hoạt động vừa sức. | Bạn có thể chọn một hoạt động từ kho tài nguyên và theo dõi điều gì giúp mình dễ chịu hơn. | SELF_GUIDED_SUPPORT |
| PHQ9_MILD_GAD7_MODERATE | MILD | MODERATE | Lo âu là nội dung nên ưu tiên trao đổi; nhóm PHQ-9 ở mức nhẹ. | Bạn nên trao đổi về lo âu với chuyên gia; các hoạt động nhẹ nhàng có thể hỗ trợ trong lúc chờ. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MILD_GAD7_SEVERE | MILD | SEVERE | Dấu hiệu lo âu ở mức nặng cần được chú ý; nhóm PHQ-9 ở mức nhẹ. | Kết quả lo âu gợi ý ưu tiên gặp chuyên gia để được lắng nghe và định hướng phù hợp. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MINIMAL | MODERATE | MINIMAL | Các dấu hiệu PHQ-9 cần được quan tâm, dù lo âu được ghi nhận rất ít. | Kết quả PHQ-9 gợi ý trao đổi với chuyên gia về những thay đổi bạn đang nhận thấy. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MILD | MODERATE | MILD | Nhóm dấu hiệu PHQ-9 cần được quan tâm; lo âu cũng xuất hiện ở mức nhẹ. | Bạn nên trao đổi với chuyên gia về thay đổi tâm trạng và chia sẻ thêm những lúc lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_MODERATE | MODERATE | MODERATE | Cả hai nhóm dấu hiệu đều đáng chú ý và nên được trao đổi cùng nhau. | Hai nhóm khó khăn nên được trao đổi cùng chuyên gia để không bỏ sót phần nào. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATE_GAD7_SEVERE | MODERATE | SEVERE | Cả hai nhóm cần được quan tâm, đặc biệt là kết quả lo âu ở mức nặng. | Bạn nên ưu tiên trao đổi với chuyên gia về lo âu và trình bày cả những thay đổi thuộc nhóm PHQ-9. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MINIMAL | MODERATELY_SEVERE | MINIMAL | Nhóm dấu hiệu PHQ-9 cần được chú ý, dù lo âu được ghi nhận rất ít. | Nhóm dấu hiệu PHQ-9 là nội dung nên được trao đổi sớm với chuyên gia. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MILD | MODERATELY_SEVERE | MILD | Nhóm PHQ-9 ở mức khá nặng, đồng thời có dấu hiệu lo âu nhẹ. | Bạn nên sớm trao đổi với chuyên gia và chia sẻ cả những lúc cảm giác lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_MODERATE | MODERATELY_SEVERE | MODERATE | Cả hai nhóm đáng chú ý, với PHQ-9 ở mức khá nặng và lo âu mức trung bình. | Cả tâm trạng và lo âu nên được đưa vào một cuộc trao đổi đầy đủ với chuyên gia. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_MODERATELY_SEVERE_GAD7_SEVERE | MODERATELY_SEVERE | SEVERE | Cả hai nhóm dấu hiệu đều đáng chú ý và nên được chuyên gia xem xét cùng nhau. | Bạn nên ưu tiên gặp chuyên gia để trao đổi riêng về từng nhóm khó khăn được ghi nhận. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MINIMAL | SEVERE | MINIMAL | Kết quả PHQ-9 ở mức nặng cần được ưu tiên trao đổi, dù lo âu ít xuất hiện. | Bạn nên ưu tiên gặp chuyên gia để trao đổi về nhóm dấu hiệu PHQ-9. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MILD | SEVERE | MILD | Nhóm PHQ-9 ở mức nặng cần ưu tiên hỗ trợ, đồng thời lưu ý lo âu nhẹ. | Bạn nên ưu tiên gặp chuyên gia và chia sẻ cả những lúc cảm giác lo âu xuất hiện. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_MODERATE | SEVERE | MODERATE | Cả hai nhóm đáng chú ý, với PHQ-9 mức nặng và lo âu mức trung bình. | Bạn nên ưu tiên gặp chuyên gia để trao đổi đầy đủ về cả tâm trạng và lo âu. | PROFESSIONAL_SUPPORT_RECOMMENDED |
| PHQ9_SEVERE_GAD7_SEVERE | SEVERE | SEVERE | Cả hai nhóm dấu hiệu đều ở mức nặng và cần được xem xét riêng trong hỗ trợ chuyên môn. | Bạn nên ưu tiên gặp chuyên gia để từng nhóm khó khăn được lắng nghe và xem xét riêng. | PROFESSIONAL_SUPPORT_RECOMMENDED |

## Remaining gates

- Owner reviews and explicitly approves the twenty exact copy variants or asks
  for changes. A draft version is not clinical or production approval.
- Frontend implementation remains traceable to the owner-supplied Stitch
  prototype while preserving the existing authenticated shell.
- Focused tests prove catalogue coverage, source ownership, safety precedence,
  stable read-only behavior and old response compatibility.
- No deployment into the shared appointment demo stack while these gates remain.
