# Vietnam safety directory policy v1

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-VN-SAFETY-DIRECTORY-001` |
| Policy version | `1.0` |
| Status | `CONTROLLED DEMO IMPLEMENTED; REAL CONTENT AND PRODUCTION RELEASE UNAPPROVED` |
| Decision date | 2026-09-17 |
| Directory owner | Content/Notification |
| Safety trigger and fallback owner | Care |
| Review authority | Authenticated `ADMIN` |
| Verification cadence | 90 days |
| Applies to | Manually selected or entered coarse areas in Vietnam |
| Decision | [ADR 0018](../adr/0018-reviewed-vietnam-safety-directory.md) |

This policy approves the directory shape and behavior needed for implementation.
It does not approve any real facility, hotline, telephone number, production
deployment, emergency dispatch, or guaranteed response.

## Directory record schema

Every versioned directory record contains:

| Field | Rule |
| --- | --- |
| `directoryEntryId` | Stable UUID; never reused for another organization or service |
| `recordVersion` | Monotonically increasing optimistic-lock and review version |
| `name` | Reviewed user-visible name, 1–200 characters after trimming |
| `type` | `FACILITY` or `HOTLINE`; no clinical capability is inferred from type |
| `phone` | Reviewed user-visible contact string, 1–64 characters; never synthesized or reformatted into a different number |
| `address` | Reviewed address up to 500 characters; required for `FACILITY`, nullable for a non-location `HOTLINE` |
| `coverage` | Non-empty reviewed set of `NATIONWIDE`, province, or district area references from one versioned area vocabulary |
| `active` | Administrative publication switch; insufficient by itself for lookup eligibility |
| `sourceName` | Accountable source or issuing organization, 1–200 characters |
| `sourceReference` | Stable source URI or controlled evidence reference, up to 2,000 characters |
| `sourceRetrievedAt` | UTC instant at which the source evidence was obtained |
| `sourceChecksum` | Optional SHA-256 of retained evidence when the source artifact can be lawfully retained |
| `reviewedBy` / `reviewedAt` | Administrator and UTC instant approving the exact record version |
| `verifiedBy` / `verifiedAt` | Administrator and UTC instant confirming the contact and coverage against its source |
| `seedKey` | Nullable immutable controlled-seed provenance key; never accepted from public/admin create requests |
| `createdAt` / `updatedAt` | UTC persistence instants |

Province and district references use canonical codes plus reviewed display
labels from the same versioned Vietnam area vocabulary. District is nullable
for province-wide or nationwide coverage. A free-text address is not used as a
geospatial coordinate or proof of coverage.

## Review and freshness

Only `ADMIN` may change or review directory records. Publication requires all
required fields, source evidence, `reviewedBy`, `reviewedAt`, `verifiedBy`, and
`verifiedAt`. A review or verification instant cannot be in the future.

An entry is `CURRENT` only while:

```text
active
AND exact version reviewed
AND exact version verified
AND verifiedAt <= now < verifiedAt + 90 days
```

At `verifiedAt + 90 days` it becomes `STALE`. Stale, inactive, unreviewed, or
unverified records are excluded from user lookup. Admin views retain them with
their reason and history. Changing `name`, `type`, `phone`, `address`,
`coverage`, or source provenance creates a new version and clears current
review/verification eligibility until that version passes review again.

Reverification records the administrator, time, exact version, source evidence,
and outcome. Deactivation is immediate for new lookups and does not erase audit
history. The implementation must use server time and deterministic boundary
tests; a client clock never decides freshness.

## Seed process

Schema migrations create structure only. Controlled directory data uses a
separate, idempotent owner seed with stable UUIDs and `seedKey` values; it must
reject drift for an already applied key.

- Local/test demo seeds use visibly labelled synthetic, non-dialable contacts
  and synthetic addresses. They never claim to be real current services.
- A real initial Vietnam dataset requires a reviewed evidence inventory, source
  retrieval metadata, an accountable administrator decision, and the same
  90-day verification rule before activation.
- Importing a row never grants review or active status by itself. Missing or
  failed evidence leaves it unavailable to user lookup.
- Production does not run a demo seed. A production seed is a separately
  approved versioned content release, not an environment bootstrap shortcut.
- Updating a seed creates a new reviewed record version; it never overwrites
  historical review evidence.

## Area lookup

The user deliberately supplies one coarse area using either a supported
province/district selector or manual text. Selected values carry canonical area
codes. Manual text may resolve only through reviewed aliases in the versioned
area vocabulary; ambiguous or unmatched input is not geocoded or guessed.

Lookup returns only `CURRENT` records whose reviewed coverage contains the
selected area. Nationwide records may accompany an area result but remain
labelled nationwide. Ordering is deterministic and non-proximity-based; V1 has
no coordinates or distance. User-facing wording is “cơ sở trong khu vực đã
chọn”, never “gần nhất”.

The browser does not request background location permission. Care and Content
do not persist precise coordinates. Raw manual-location text is not written to
assessment records, events, analytics, or logs; only an accepted canonical area
code may be used for the immediate lookup and privacy-reviewed aggregate
operational metrics.

## Safety triggers and fallback

Positive PHQ-9 item 9 under its versioned rule and explicit “Tôi cần hỗ trợ
ngay” open the same flow. The explicit action requires no assessment. Both are
available to anonymous, `FREE`, `PLUS`, and `PREMIUM` users without AI or paid
entitlement checks. A screening band alone does not trigger this flow.

Care always owns and returns the approved local safety guidance. It may enrich
that response with a bounded synchronous Content lookup. The composition
distinguishes `RESULTS`, `EMPTY`, `INVALID_AREA`, and `UNAVAILABLE`; every state
retains the Care fallback and must not imply that no help exists.

The controlled-Capstone fallback remains:

> Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.

No specific number is approved by this policy. Production safety wording and
each real directory record still require their stated domain, legal, privacy,
content, and operational gates.

## Prohibited behavior

The feature never automatically calls, opens a call without a deliberate user
action, shares location, sends a safety email, notifies a third party, starts
background geolocation, diagnoses, stratifies suicide risk, promises 24/7 human
monitoring, or guarantees that a listed service will respond. AI, Kafka, Redis,
WebSocket, email, and notification delivery are not dependencies of the safety
decision or fallback.

## Implementation and evidence gate

Controlled-demo runtime is publishable only while all of the following agree and pass; real content and production release remain separately gated:

- additive Content provider and Care composition OpenAPI contracts;
- append-only Content persistence, review history, seed provenance, and data
  dictionary;
- Care deadline, response validation, empty/error fallback, and both triggers;
- frontend area input, results, empty, invalid, stale-safe, unavailable, and
  accessible keyboard/screen-reader states;
- provider, consumer, fixture-browser, and live cross-stack tests classified
  truthfully; and
- privacy/safety review of real content before any real record is enabled.
