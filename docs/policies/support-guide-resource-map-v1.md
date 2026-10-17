# Support Guide resource map v1

Status: implemented for MB-646 controlled development/demo use. Product Owner
delegated construction of this mapping on 2026-10-16. This is product and
implementation authority only; it is not clinical review or production approval.

- Version: `mb-support-guide-resource-map-v1` (`SupportGuideResourceMap`).
- Owner: Care. Applies only when a NEW Support Guide is materialized. Saved
  Guides, including earlier three- or four-resource snapshots, are never
  re-selected, truncated or rewritten.
- Guide envelope, `mb-support-guide-capstone-v1`, database schema and API
  contracts are unchanged. The saved snapshot records each exact resource ID,
  content version and eligibility publication, which is the audit trail of a
  selection. The map version itself is not persisted.

## Selection rule

1. Look up the row for the saved PHQ-9/GAD-7 band pair. The 20 row keys equal
   the base meaning codes in the
   [combined screening meaning draft](combined-screening-meaning-draft-v1.md).
2. Send every candidate in that row, in order, to the existing Content
   `resource-eligibility:resolve` boundary as an exact `contentVersion` query.
   The query uses the candidate's own domain, that domain's saved level and
   stored domain pathway, `vi-VN` and `PRIMARY` eligibility. `PRIMARY_OR_ADJUNCT`
   remains a SupportPlan optional-slot rule and is not used by Guide.
3. Keep the first two `ELIGIBLE` results in row order, deduplicated by exact
   resource identity. Stale, withdrawn, ineligible or unavailable candidates are
   skipped; nothing outside the row is substituted.
4. Resource status: `AVAILABLE` when two are kept, or when fewer are kept and
   the rest were only ineligible. `PARTIAL` when fewer than two are kept because
   of stale, withdrawn or unavailable results. With none kept: `UNAVAILABLE`,
   then `STALE`, then `EMPTY`.

Safety and pathway remain independent of resources. Positive PHQ-9 item-9
evidence keeps the stored safety guidance first. Professional pairs keep
specialist discovery as the primary next action. On professional pairs Care
omits the approved "start by choosing a resource" sentence, so self-help is
never presented as the first step. Severe bands alone add no safety signal.

## Candidate pool

All six candidates are MB-556 reviewed catalogue items at content version `0`
with `content-eligibility-v1` PRIMARY declarations
(`content-notification-service/migrations/review1/6_seed_mb556_reviewed_resource_catalogue.sql`).
That seed is source evidence only; runtime eligibility comes from live Content.

| Short name | Resource | Domain | PRIMARY levels and pathways |
| --- | --- | --- | --- |
| SMALL_ACTIVITY | `…0208` Bắt đầu bằng một hoạt động nhỏ | Depressive | All PHQ-9 levels; all pathways |
| DEPRESSION_SIGNS | `…0205` Hiểu các dấu hiệu thường gặp của trầm cảm | Depressive | All PHQ-9 levels; all pathways |
| SLOW_BREATHING | `…0201` Thở chậm trong 5 phút | Anxiety | All GAD-7 levels; all pathways |
| ANXIETY_SIGNS | `…0206` Hiểu lo âu và những dấu hiệu thường gặp | Anxiety | All GAD-7 levels; all pathways |
| WORRY_TIME | `…0211` Dành một khoảng thời gian riêng cho nỗi lo | Anxiety | Minimal/Mild; self-guided only |
| GROUNDING | `…0202` Grounding: quay về với hiện tại | Anxiety | Minimal/Mild; self-guided only |

## Rows

Order is the candidate order. The first two eligible candidates are kept.

| Row (meaning code) | Pathway | Ordered candidates |
| --- | --- | --- |
| PHQ9_MINIMAL_GAD7_MINIMAL | Self-guided | SMALL_ACTIVITY, SLOW_BREATHING, GROUNDING |
| PHQ9_MINIMAL_GAD7_MILD | Self-guided | SLOW_BREATHING, WORRY_TIME, GROUNDING |
| PHQ9_MILD_GAD7_MINIMAL | Self-guided | SMALL_ACTIVITY, DEPRESSION_SIGNS |
| PHQ9_MILD_GAD7_MILD | Self-guided | SMALL_ACTIVITY, SLOW_BREATHING, GROUNDING |
| PHQ9_MINIMAL_GAD7_MODERATE | Professional | ANXIETY_SIGNS, SLOW_BREATHING |
| PHQ9_MINIMAL_GAD7_SEVERE | Professional | ANXIETY_SIGNS, SLOW_BREATHING |
| PHQ9_MILD_GAD7_MODERATE | Professional | ANXIETY_SIGNS, SLOW_BREATHING |
| PHQ9_MILD_GAD7_SEVERE | Professional | ANXIETY_SIGNS, SLOW_BREATHING |
| PHQ9_MODERATE_GAD7_MINIMAL | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_MODERATE_GAD7_MILD | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_MODERATELY_SEVERE_GAD7_MINIMAL | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_MODERATELY_SEVERE_GAD7_MILD | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_SEVERE_GAD7_MINIMAL | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_SEVERE_GAD7_MILD | Professional | DEPRESSION_SIGNS, SMALL_ACTIVITY |
| PHQ9_MODERATE_GAD7_MODERATE | Professional | DEPRESSION_SIGNS, ANXIETY_SIGNS, SMALL_ACTIVITY, SLOW_BREATHING |
| PHQ9_MODERATELY_SEVERE_GAD7_MODERATE | Professional | DEPRESSION_SIGNS, ANXIETY_SIGNS, SMALL_ACTIVITY, SLOW_BREATHING |
| PHQ9_SEVERE_GAD7_MODERATE | Professional | DEPRESSION_SIGNS, ANXIETY_SIGNS, SMALL_ACTIVITY, SLOW_BREATHING |
| PHQ9_SEVERE_GAD7_SEVERE | Professional | DEPRESSION_SIGNS, ANXIETY_SIGNS, SMALL_ACTIVITY, SLOW_BREATHING |
| PHQ9_MODERATE_GAD7_SEVERE | Professional | ANXIETY_SIGNS, DEPRESSION_SIGNS, SLOW_BREATHING, SMALL_ACTIVITY |
| PHQ9_MODERATELY_SEVERE_GAD7_SEVERE | Professional | ANXIETY_SIGNS, DEPRESSION_SIGNS, SLOW_BREATHING, SMALL_ACTIVITY |

Construction rules:

- Self-guided rows follow the approved draft.3 rationale for the row. Minimal
  and Mild/Mild get one small mood activity and one short calming practice.
  Mild anxiety gets a short relaxation exercise and worry noticing. Mild mood
  gets a small activity first.
- Professional rows use only candidates that are PRIMARY for the professional
  pathway. They target the domain or domains at Moderate or above, starting
  with the domain the approved meaning names first. They lead with
  understanding the signs, which also prepares a conversation with a specialist.

## Known coverage gaps

- `…0201` was bumped to content version 1 by
  `review1/10_align_mb603_timed_practice_duration.sql` without an eligibility
  publication for version 1. Where that migration ran, `…0201@0` resolves
  `STALE`. Rows that list a later candidate fall back to it. The four
  anxiety-only professional rows then save one resource (`PARTIAL`). Closing
  this needs a reviewed eligibility publication for the new version plus map v2.
- `…0212` Chuẩn bị trước khi trao đổi với chuyên gia fits professional rows, but
  it is ADJUNCT only, so Guide does not use it.
- Domain/clinical review of every row remains a release gate, as it is for the
  draft.3 meaning copy.
