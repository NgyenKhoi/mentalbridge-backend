# MB-687 private groups

Owner: Community / Device 1. Base: merged MB-685 `ef05d0bd`; accepted ADR 0042,
policy v1 and MB-684 permission/acceptance tables remain authoritative. This is
backend work; MB-688 owns clients. No new policy or UI-ready approval is inferred.

## Accepted states and implementation slices

| State/event | Required result |
| --- | --- |
| Create | Creator alone is OWNER; private name; configurable cap 20–30, default 30. |
| Invite | OWNER/ADMIN only; text-free minimal invitation; explicit recipient acceptance; pending does not reserve seats. |
| Accept | Current inviter authority, active account/group, no bilateral block and a free seat; atomically allocate current epoch. |
| Decline/expire/revoke | No membership; decline suppresses reinvitation; seven-day expiry; role loss/archive/block/restriction invalidates pending. |
| Role/ownership | OWNER changes ADMIN/MEMBER; transfer to current active ADMIN leaves exactly one OWNER, former owner ADMIN. |
| Leave/remove | Immediate loss of history/unread; active OWNER must transfer/archive; ADMIN removes MEMBER only. |
| Rejoin | New epoch, no earlier history/read reference, no unread inherited from prior epoch. |
| Block in shared group | Group stays usable; pair's content/unread/projections filtered before pagination; no hidden counters/order changes. |
| Archive | No sends/invites/management writes; current members retain eligible history and may self-leave. |
| Report | Currently readable exact message only; encrypted isolated snapshot, no unrelated transcript or ADMIN browse authority. |

1. Group lifecycle: migration/canonical model, active accepted REST subset,
   role/cap/invitation transactions and real PostgreSQL regression tests.
2. Group message visibility: reuse encrypted saves/outbox/read state with
   membership epoch, current eligibility, bilateral filtering and concurrency tests.
3. Report intake and delivery evidence: bounded exact-message hook, minimized
   group facts through existing relay, contract/Kafka tests, full owner gate and CI.

Implementation and exact-head CI evidence are in progress; no completion claim.

Lifecycle checkpoint: 30 focused tests passed on disposable PostgreSQL, including
ten group workflow cases, cap configuration, closed/frozen OpenAPI compatibility,
canonical provider contract and exact migration/table inventory. The original
operation inventory needed the nine newly implemented routes; it remains exact
rather than changing implemented/planned assertions.
