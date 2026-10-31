# Identity terminal account soft deletion — DEMO/V1

- **Scope:** Identity-owned demo account closure. The [Product Owner decision in MB-778, comment 10308](https://vunguyenkhoi47.atlassian.net/browse/MB-778?focusedCommentId=10308), supersedes the earlier enterprise-policy proposal. MB-778 remains in review until this narrow document and its checks are reviewed.
- **Authority:** This addendum follows the existing [Identity account and session policy](../modules/identity-security-policy.md), including terminal `DELETED` and denial of authentication and recovery. It describes the demo scope, not a production personal-data erasure policy or legal-compliance determination.

## Existing Identity behavior

An authorized deletion flow marks the account `status=DELETED` and sets `deletedAt` and `updatedAt`; DELETED is terminal. The Identity coordinator checks an active recorded hold before deletion retry and revokes the account's refresh sessions when it transitions to DELETED. Repeated requests must not reactivate the account or create a new account.

A DELETED account cannot log in, refresh or use password recovery. Previously issued access JWTs are rejected for protected requests at the services covered by [MB-779](https://github.com/NgyenKhoi/mentalbridge-backend/pull/233): Identity checks the account locally; Care, Community and Consultation check Identity after JWT validation. An unavailable Identity authorization dependency fails closed for those remote checks. This does not assert coverage for other services or deployment environments.

[`AccountEntity.markDeleted()`](../../identity-service/src/main/java/com/mentalbridge/identity/account/AccountEntity.java) changes status and timestamps; it does not erase identifying fields. The account row, `email`, `password_hash` and the current unique `CITEXT` email constraint remain in place, as defined by the [Identity baseline migration](../../identity-service/src/main/resources/db/changelog/changes/001-identity-baseline.sql). The retained email stays reserved: **same-email re-registration is not supported in DEMO/V1**.

## Holds, security and audit

Keep existing Identity deletion/hold enforcement, authorization and minimized audit evidence. An active hold blocks the applicable coordinator retry; releasing a hold does not itself perform deletion or restore authentication. Do not expose raw email, passwords, tokens, clinical content or other sensitive user data in audit evidence or logs. A hold does not make a DELETED account active.

## User-facing wording and limits

Describe the outcome as **“tài khoản đã được đóng/vô hiệu hóa”** (the account has been closed/deactivated). Do not say **“đã xóa vĩnh viễn mọi dữ liệu”** or otherwise promise that all personal data has been permanently erased: this demo retains the Identity account and does not perform general hard deletion or cross-service cascading erasure.

Use synthetic demo identities and data, not real private health information. Physical field erasure, same-email re-registration, a more granular hold model, audit/backup retention guarantees, schema changes and MB-777 production intake or cross-service orchestration are outside DEMO/V1 and require separate assessment before any real-user deployment. No claim of production readiness or legal compliance follows from this demo decision.
