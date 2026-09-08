# Identity account and session policy

This document is the source of truth for Identity account lifecycle, role assignment, local-password authentication, refresh sessions, and account-recovery behavior. API contracts, Liquibase changes, implementation, and tests must preserve these decisions.

## Account lifecycle

Identity uses these account states:

| State | Authentication behavior | Allowed transitions |
| --- | --- | --- |
| `PENDING_EMAIL_VERIFICATION` | Password login, refresh, and protected access are denied. Verification and resend requests are allowed subject to rate limits. | `ACTIVE`, `DISABLED`, `DELETION_PENDING` |
| `ACTIVE` | Password login and refresh are allowed unless a temporary login lock is active. | `DISABLED`, `DELETION_PENDING` |
| `DISABLED` | Login, refresh, and new protected actions are denied. Existing refresh sessions are revoked on entry. | `ACTIVE`, `DELETION_PENDING` |
| `DELETION_PENDING` | Login, refresh, and new protected actions are denied. Existing refresh sessions are revoked on entry. | `ACTIVE` only when the deletion workflow is still cancellable; otherwise `DELETED` |
| `DELETED` | All authentication and recovery operations are denied. | Terminal |

A temporary credential lock is represented by `lockedUntil`, not by an account state. It blocks password authentication until the instant passes but does not invalidate an otherwise active refresh session. A successful password login clears the consecutive failure count.

Email verification moves a pending account to `ACTIVE`. Repeating verification with the same consumed challenge returns the same successful outcome when the account is already verified; it does not issue another account event.

Specialist approval is not an Identity account state. A specialist registration receives the `SPECIALIST` actor role after email verification, while profile review and eligibility remain authoritative in Consultation Service. Identity role membership alone never authorizes specialist-only business access that requires approval. The current scope does not collect specialist verification documents.

## Role matrix

Each account has exactly one immutable actor role included in access tokens. Resource owners still enforce resource-level and domain-specific authorization.

| Capability | `USER` | `SPECIALIST` | `ADMIN` |
| --- | ---: | ---: | ---: |
| Public self-registration | Yes | Yes | No |
| Authenticate and manage own sessions | Yes | Yes | Yes |
| Access own account facts | Yes | Yes | Yes |
| Use end-user care capabilities | Yes | No | No |
| Request specialist workflows | No | Yes, subject to Consultation approval | No |
| Read or change another non-admin account's state | No | No | Yes |

Public registration accepts exactly one actor type: `USER` or `SPECIALIST`. It never creates an `ADMIN` account, and neither an administrator nor the account owner can promote, demote, or replace an account role. The initial deployment provisions exactly one dedicated `ADMIN` account through an operator-controlled bootstrap using externally supplied credentials. The database permits at most one `ADMIN`; deployment readiness requires that this account exists. Normal account-administration APIs do not mutate the dedicated administrator account. Account state mutations use optimistic concurrency and emit minimized audit facts.

## Password and credential policy

- Passwords contain 12 to 128 Unicode characters and at most 72 UTF-8 bytes. They must not be silently truncated or normalized before hashing.
- Passwords are hashed with BCrypt using cost factor 12. Plaintext passwords, recovery credentials, and hashes never enter logs, events, metrics, or API responses.
- Login and recovery-request failures use a generic response that does not reveal whether an email is registered.
- Five consecutive failed password attempts lock password login for 15 minutes. The counter and lock update are atomic. Verification-resend and recovery limits use a keyed fingerprint of the normalized email and purpose for eligible, ineligible, and unknown subjects; a future trusted-edge network key may supplement this subject limit without changing the public response.
- A successful password reset or authenticated password change revokes every refresh session for the account. Previously issued access tokens expire naturally within their short lifetime; resource owners must not treat an offline JWT as proof that the account remains enabled for high-risk current-state decisions.

Security values are typed configuration with the policy values above as reviewed defaults. Lower production values are rejected at startup. A future change to token lifetimes, signing semantics, or revocation behavior requires an explicit compatibility and threat review.

## Access tokens

- Access tokens are signed JWTs using an asymmetric key pair. Identity holds the private key; resource services receive only trusted public-key material.
- The access-token lifetime is 15 minutes with no sliding extension.
- Required claims are `iss`, `sub`, `aud`, `iat`, `nbf`, `exp`, `jti`, and `roles`. `sub` is the opaque account UUID and `roles` contains exactly one stable role code for v1 consumer compatibility.
- Tokens contain no email, profile, health, specialist-verification, consent, or other mutable sensitive data.
- Consumers validate the signature, issuer, audience, time claims, and required authorization on every request. Clock skew tolerance is at most 60 seconds.

## Refresh sessions

- A refresh credential is an opaque value with at least 256 bits of cryptographic entropy. Only its SHA-256 hash is persisted because the generated credential is already high entropy.
- A refresh session expires after 30 days and never extends beyond that session family's original absolute expiry.
- Every successful refresh rotates the credential in one transaction: consume the presented session, create one successor linked by `rotatedFromId`, and return the new token pair. Concurrent use of the same credential has one winner.
- Reuse of a rotated or revoked refresh credential revokes every active descendant in the same rotation family. The response is a generic invalid-session problem and a minimized security audit fact is emitted.
- Logout revokes the current refresh-session family and is idempotent. Logout-all, password change/reset, transition to `DISABLED` or `DELETION_PENDING`, and confirmed credential compromise revoke all refresh sessions for the account.
- Client-supplied device labels are optional, length-bounded, treated as untrusted display text, and never used as an authorization factor. IP and user-agent values may be retained only as keyed, privacy-minimized hashes for bounded security correlation.

Identity serializes refresh rotation at the persisted session boundary and stores the completed idempotent outcome for a bounded retry window. A timeout does not permit minting a second successor for the same logical refresh request.

## Email verification and recovery

- Verification and recovery use separate, purpose-bound, one-time challenges. A challenge cannot be exchanged for a different purpose or account.
- Only a one-way hash is stored. A challenge is consumed atomically with the account or credential change and cannot be reused.
- Email-verification challenges expire after 24 hours. Password-recovery challenges expire after 15 minutes.
- Issuing a new challenge invalidates prior unconsumed challenges for the same account and purpose.
- Resend and recovery-request endpoints return an empty generic accepted response. Every valid request consumes the same privacy-minimized normalized-email subject budget regardless of account existence or eligibility: no more than three requests per purpose per hour and a 60-second interval. A rejected request returns `429` with `Retry-After` without creating a challenge or delivery.
- Password recovery does not activate, re-enable, or cancel deletion for an account. Disabled and deleted accounts receive no usable recovery challenge.
- Brevo is a delivery adapter only. Identity creates, hashes, expires, consumes, and audits challenges; provider responses never become authentication authority. Delivery runs after the state transaction commits, and a provider failure is recorded without recipient, challenge, or provider detail while the public request remains generically accepted.
- Local frontend integration may write a verification or recovery URL for a synthetic account to a purpose-specific ignored private file. This adapter is disabled by default, never logs the recipient or challenge, and fails startup unless exactly the `dev` profile is active.

## Required verification scenarios

Contract and implementation work must cover invalid transitions, duplicate normalized email, password boundaries, generic unknown-account behavior, expired and reused challenges, resend limits, atomic failure counters, concurrent refresh, refresh replay, idempotent logout, all-session revocation, rejection of public `ADMIN` registration, the single-administrator database constraint, and absence of secrets or sensitive identity data from errors, logs, metrics, and events.
