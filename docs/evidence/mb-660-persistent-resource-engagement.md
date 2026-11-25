# MB-660 persistent resource engagement evidence

Content owns optional resource bookmarks and weekly Bingo state. Bookmarks bind
to the exact reviewed publication snapshot saved by the owner. Bingo stores an
owner/week/timezone board plus independently reversible self-confirmations;
verified evidence is recomputed from exact-version resource completion or a
qualifying persisted practice session.

The migration adds exact snapshot foreign keys and optimistic board versions.
Strong ETags make one identical retry idempotent and reject a stale state-changing
request. Read/write paths bind every row to the authenticated owner. Historical
boards remain deterministic across publication updates or withdrawal, while
verified evidence disappears if its exact authoritative completion is revoked or
replaced by another content version.

The ADR is `docs/adr/0037-persistent-resource-engagement.md`; bounded product
semantics are in `docs/policies/resource-engagement-policy-v1.md`. OpenAPI,
logical schema, field dictionary, module documentation, product blueprint, and
requirements traceability were updated in the same change.

## Verification

- `npm run quality`
- 177 unit, policy, contract, and HTTP tests passed.
- 67 PostgreSQL integration tests passed through Testcontainers, including exact
  bookmark snapshot refresh/delete, owner isolation, concurrent Bingo writes,
  stale-version rejection, idempotent retry, self-confirmation undo, and
  exact-version verification appearance/revocation.
- OpenAPI and all 21 owner migrations passed repository validation; TypeScript
  build completed.
