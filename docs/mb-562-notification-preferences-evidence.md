# MB-562 notification preference evidence

## Delivered boundary

- `GET /api/v1/notification-preferences` persists and returns stable defaults for the JWT owner.
- `PATCH /api/v1/notification-preferences` supports partial or complete aggregate updates guarded by `If-Match`.
- The shared aggregate contains `IN_APP`, `EMAIL`, and future `PUSH` choices; six independent content groups; a local quiet window with IANA timezone; and email cadence plus explicit wellbeing/resource opt-ins.
- `PUSH` remains preference-only. This slice adds no device token, provider send, producer decision, quiet-hour bypass, safety inference, or automatic safety email.
- Request ownership comes only from the verified JWT subject. No owner identifier or sensitive journal, assessment, chat, self-report, or provider content enters the contract.

## Persistence and compatibility

Migration `10_persist_notification_preferences.sql` converts the unused legacy channel/category matrix into one atomic owner aggregate. Existing owner/channel/category choices are mapped forward, while new owners receive conservative channel defaults (`IN_APP` on, `EMAIL` and `PUSH` off). Strong versions prevent cross-device lost updates.

## Verification

- HTTP tests cover default creation, partial/full preference fields, wrong-owner isolation, invalid time/timezone/unknown fields, missing/stale versions, and bounded dependency failure.
- PostgreSQL integration covers constraints, full persistence, reload through another repository instance, owner isolation, timezone changes, and stale writes.
- Contract tests pin authenticated operations, ETag/no-store behavior, every channel/content group, email opt-ins, and the sensitive-data exclusion.
- The frontend contract snapshot, BFF, browser client, settings states, and fixture browser journey consume this same owner model without a parallel local preference copy.
