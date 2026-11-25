# Private specialist credential review

- Decision ID: `MB-SPECIALIST-CREDENTIAL-001`
- Status: Accepted product direction; runtime delivery requires private storage configuration and review checks
- Decision date: 2026-10-13
- Owner: Consultation
- Authority: Product owner's explicit request for real backend persistence and specialist/admin-only originals

## Decision

Consultation owns specialist credential metadata, upload authorization, review state, and badge visibility. A specialist may upload a PDF, JPEG, or PNG up to 10 MiB. Originals use Cloudinary authenticated storage and are accessible only through a five-minute signed link issued after Consultation checks the caller's SPECIALIST ownership or ADMIN role. No original URL, storage key, or file bytes appear in public profile or discovery contracts.

New documents begin `PENDING`. Only ADMIN can mark one `VERIFIED` or `REJECTED`. A specialist may choose whether a verified document's badge is visible; pending or rejected documents cannot be displayed as verified. The verified badge may contain only the reviewed title/type, never the original file or serial number. Review is distinct from approval of the six-field public profile and does not make an unapproved specialist discoverable.

The reviewed professional languages extend from `vi` and `en` to `zh` and `fr`. Existing profile and approved-amendment review rules apply unchanged. Consultation remains exactly 60 minutes through in-app chat or enabled in-app video. The proposed 30/45-minute and in-person choices are not enabled or stored as booking capabilities.

This decision prospectively amends the earlier no-specialist-document clauses in [ADR 0005](0005-consultation-billing-and-credit-settlement.md), [ADR 0014](0014-appointment-specialist-and-consultation-continuity.md), and the current [specialist policy](../policies/consultation-specialist-policy-v2.md). Historical approvals remain historical; no existing profile receives an invented verified document.

## Compatibility and privacy

The document table and API are additive. The language constraint expands in place. Cloudinary configuration is disabled by default in environments without approved private storage; upload and original-file access fail closed with 503 while profile reads remain available. A reviewed document does not alter a booked appointment or existing profile snapshot. Provider keys stay in environment secrets, and originals are never stored in PostgreSQL.
