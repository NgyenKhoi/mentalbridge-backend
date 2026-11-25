# ADR 0028: Daily wellbeing email digest

## Status

Accepted for MB-514.

## Decision

Content/Notification evaluates the persisted MB-562 preference aggregate. A daily digest is eligible only when notifications and EMAIL are enabled, the wellbeing opt-in is enabled, cadence is `DAILY_DIGEST`, the configured local send time has passed, the user is outside quiet hours, and at least one real item is pending. Late scheduler runs may send until the local day ends; an empty day sends nothing.

The digest may contain reviewed, unfinished resource titles from the materialized SupportPlan assignment and generic Journal/emotion prompts derived from factual completion flags. Each item also respects its shared MB-562 content-group switch. It never contains Journal text, assessment answers, chat content, provider payloads, or an automatic safety message. One optional resource email may be sent per local day when both the resource content group and its explicit opt-in are enabled and its configured time has passed.

`wellbeing_email_delivery` is an auditable delivery ledger, not another preference store. Its unique owner/local-date/kind key provides concurrency-safe dedupe. Failed provider/contact attempts retain a bounded safe error code and may be retried; successful or cancelled rows are terminal. Brevo receives approved rendered copy only, while recipient email is resolved just-in-time from Identity through a scoped service-token endpoint.

## Consequences

- Changing channel, timezone, quiet hours, cadence, opt-ins, or send times in MB-562 immediately changes scheduler behavior.
- Provider identifiers and aggregate counts may be persisted; recipient addresses and source health content are not persisted in the digest ledger or logged.
- Resource reminders are capped at one email per local day. Appointment reminders remain outside this flow.
