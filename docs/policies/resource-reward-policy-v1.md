# Resource reward policy v1

| Field | Value |
| --- | --- |
| Policy version | `resource-reward-v1` |
| Effective from | `1970-01-01` for verified existing Content facts |
| Decision | [ADR 0036](../adr/0036-authoritative-resource-rewards.md), `MB-RESOURCE-REWARD-001` |
| Owner | Content/Notification Service |
| Status | Implemented for controlled development/demo use |

## Eligible facts and XP

| Fact | Evidence | XP | Bound |
| --- | --- | ---: | --- |
| Resource completion | Terminal owner/date/resource progress joined to the exact reviewed publication snapshot | 10 | First four completions per local date, ordered by first completion instant then resource ID |
| Verified practice | Plan-bound practice session joined to the exact reviewed publication snapshot, with start instant and 60–7200 measured seconds | 5 | One earliest session per resource and local date |

The daily completion cap bounds XP without marking later participation as a
failure. Eligible facts never lose XP because a day is missed. A source record
removed through an authorized correction no longer contributes on the next
read. Retrying, revisiting, changing checklist selections, or repeating the same
resource on the same local date cannot create a duplicate policy fact.

## Display levels

Levels begin at total XP thresholds `0`, `10`, `40`, `90`, `160`, and `250`.
Level 5 is the highest v1 display level; factual total XP can continue above it.
Levels are voluntary recognition labels and cannot gate resources, SupportPlan,
safety, professional support, packages, or any basic product capability.

## Safety and language

The UI describes XP as recognition of saved self-care participation. It must not
call XP treatment progress, recovery, adherence, wellbeing, mood, improvement,
or a mental-health score. Users may hide the entire presentation and restore it
later. No leaderboard, public rank, penalty, decay, streak substitution, or
emotional badge is permitted.

## Versioning

Each grant reports the rule version effective for its local date. A later policy
adds a new catalog entry with a later effective date; it does not rewrite this
file or reinterpret older facts. Grant identity includes the rule version so an
audit can explain the exact calculation.
