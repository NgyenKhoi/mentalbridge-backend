# ADR 0044: Secure native push delivery

- Status: Accepted for MB-675 implementation
- Date: 2026-10-22
- Decision ID: `MB-NATIVE-PUSH-DELIVERY-001`
- Owner: Content/Notification

## Context

MentalBridge already persists an owner-level PUSH preference and authoritative
in-app notifications. Native delivery requires an owner-scoped device lifecycle,
provider isolation, bounded retries, and privacy-minimized payloads. A device or
provider acknowledgement must never become notification truth.

## Decision

Content/Notification owns device registrations and push delivery attempts. The
first provider adapter uses Expo Push Service because the approved mobile stack
is Expo/React Native and Expo accepts both Android and iOS installations. The
adapter remains behind a local provider boundary so a later FCM/APNs adapter does
not change the public registration contract.

`PUT /api/v1/push-devices/{installationId}` is a natural idempotent replacement
operation. The verified JWT supplies the owner; client-supplied owner identifiers
are prohibited. One installation and one provider token may each be active for
only one owner, so registration atomically revokes every prior assignment even
when both the owner and token change. An identical PUT is a no-op and does not
advance eligibility timestamps or versions. `DELETE` is an idempotent
owner-only sign-out/revocation transition. Permission denial keeps a durable
disabled registration, while provider `DeviceNotRegistered` makes it invalid.
Both commands share a database-atomic per-owner fixed-window rate limit.

Provider tokens are encrypted with AES-256-GCM under an externally injected key.
Only a SHA-256 token hash is used for uniqueness and rotation. Tokens, provider
payloads, and provider responses are excluded from logs and REST responses.

The worker derives one delivery attempt per authoritative inbox notification and
active device. Top-level/PUSH preference, device permission, quiet hours, and
notification retention remain authoritative and are rechecked before submission.
Deletion atomically suppresses unsent attempts. Claims use database locking, at
most three provider submissions, and bounded exponential retry. Expo ticket
acceptance is recorded as `ACCEPTED`, never `DELIVERED`. Bounded delayed receipt
lookups record `CONFIRMED`, provider rejection, or unknown outcome and invalidate
the device when Expo reports `DeviceNotRegistered`. A successful Expo receipt
still does not prove that the user saw or physically received the notification.

Every push contains only a reviewed title, generic body, notification UUID, and
closed action pointer. It excludes assessment answers, Journal/emotion text,
crisis status, chat content, and AI payloads. The authenticated app fetches the
authoritative notification after opening.

Provider execution is disabled by default. Enabling it requires an Expo access
token and token-encryption key. Mocked tests prove adapter behavior but do not
claim production or real-device delivery. Android staging evidence remains gated
by MB-666 credentials and device availability.

## Consequences

- Multiple installations per owner are supported without using hardware IDs.
- Reinstall/token rotation and cross-owner token reassignment are explicit and auditable.
- Provider outages cannot change inbox truth and do not hold a database transaction open.
- Expo acceptance is observable without overstating end-device delivery.
- Disabling the worker is the rollback switch; the additive tables remain safe for rolling deployment.
