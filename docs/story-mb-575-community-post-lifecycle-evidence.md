# MB-575 — Community personal-story lifecycle evidence

Jira: https://vunguyenkhoi47.atlassian.net/browse/MB-575

## Delivered contract and behavior

- `POST /api/v1/community/posts` requires a printable 16–128 character `Idempotency-Key`, validates one to three governed topics and zero to ten media IDs, and accepts up to 5,000 Unicode code points.
- Create idempotency is scoped by Community owner. An identical retry returns the same post; reuse for different normalized input returns `IDEMPOTENCY_KEY_REUSED`.
- Only the authenticated owner's unattached (or same-post), `READY` media can be attached. All invalid media cases share `COMMUNITY_MEDIA_NOT_ATTACHABLE` without disclosing another owner or processing state.
- Owner create/detail/update responses use a quoted `ETag`. `PATCH` and `DELETE` require the exact value through `If-Match`; stale commands return `COMMUNITY_POST_VERSION_MISMATCH`.
- Cross-owner mutations share `COMMUNITY_POST_NOT_FOUND` with absent, hidden, removed, and deleted posts.
- Delete is a transactional `OWNER_DELETED` tombstone. Public feed/detail reads stop returning the post immediately, while its content, timestamps, state, and version remain in Community-owned storage.
- A neutral Community-local profile is provisioned on first publish when no profile exists. No Identity business, Care, Journal/AI, assessment, SupportPlan, or specialist-context call or event carries story text.

## Persistence evidence

- Liquibase change `0003-community-post-lifecycle` adds the nullable legacy-compatible idempotency provenance pair and the unique owner-scoped idempotency index.
- The executable migration, canonical PostgreSQL model, field dictionary, JPA model, and OpenAPI status/header declarations are updated together.

## Verification

- `CommunityPostLifecycleIntegrationTests` covers Unicode boundaries, owner-scoped idempotent retry/conflict, neutral profile provisioning, exact ETag edits, stale updates, cross-owner deletion, immediate tombstone visibility, and owner/READY media enforcement.
- Existing feed integration tests continue to cover newest-first paging, governed topic filtering, block/hidden fail-closed behavior, deleted-author presentation, and READY-only media exposure.
- `CommunityOpenApiContractTests` parses the frozen v1 contract and asserts only delivered operations are marked implemented.
- Frontend route/component tests and Playwright cover BFF header propagation, bounded validation, stable browser retry keys, owner-only controls, stale-version reconciliation, and the create/edit/delete journey.
