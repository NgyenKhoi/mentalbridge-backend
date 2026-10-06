# MB-619 appointment dispute evidence

MB-619 adds a Consultation-owned, one-per-appointment dispute aggregate for
eligible settled session outcomes.

- USER and assigned SPECIALIST use separate role-protected routes and receive
  `404` for another appointment.
- Open commands are idempotent, bounded to 24 hours after settlement, and store
  only stable reason plus optional operational evidence type/time.
- `AppointmentSettlementGate` rejects earning eligibility while a dispute is
  open and after a `RELEASE_USER_CREDIT` resolution. Only an upheld recorded
  outcome reopens that gate; the decision has no Notification, Care, Realtime,
  or billing-provider dependency.
- ADMIN resolution is optimistic and replay-safe. It records prior/resulting
  appointment and session outcome plus the exact credit action.
- Returning a terminal credit appends one `ADJUSTED_RELEASED` ledger fact before
  restoring availability. The earlier `CONSUMED` or `FORFEITED` fact is retained.
- PostgreSQL integration tests cover wrong actor, expired window, participant
  roles, open/resolve replay, simultaneous open/resolve races, invalid
  resolution pairing, both earning-gate outcomes, and exactly-one adjustment.
