# MB-382 appointment chat evidence

## Ownership and policy

Consultation remains authoritative for appointment participants, modality,
status and schedule. Its internal eligibility projection uses the server clock:
entry opens ten minutes before start, send is allowed only in
`[scheduledStartAt, scheduledEndAt)`, and ended/cancelled/rescheduled sessions
retain read-only history. A non-participant receives the same not-found outcome
as an unknown appointment.

Realtime owns the appointment-bound conversation and encrypted messages. It
rechecks Consultation for subscribe, send and history, validates the returned
participant binding, and fails closed on timeout or malformed responses.

## Browser credential decision

The authenticated BFF exchanges the HttpOnly session for a random 256-bit,
one-use ticket with a maximum 30-second lifetime and the configured non-secret
public socket endpoint. Realtime stores only a hashed ticket key; the
short-lived Redis record encrypts the reusable bearer with
AES-256-GCM. Socket authentication atomically consumes the ticket with `GETDEL`
and the socket disconnects at the original Identity expiry.

## Verification

| Check                                                | Result                                                                                                |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Consultation OpenAPI contract test                   | Passed                                                                                                |
| Consultation package compile (`-DskipTests package`) | Passed                                                                                                |
| Realtime typecheck, contract validation and build    | Passed                                                                                                |
| Realtime lint                                        | Passed                                                                                                |
| Realtime Vitest unit/contract/HTTP suite             | 42/42 passed across 8 files using the bundled config loader                                           |
| Frontend quality gate                                | Format, lint, typecheck, contract checks, 538/538 tests across 113 files, and production build passed |
| Docker-backed Consultation/Realtime integration      | Attempted, but Docker/Testcontainers was unavailable in this environment                              |

The integration suite covers wrong actors, waiting/active/ended windows,
cancellation, rescheduling, one-use credential exchange, reconnect, duplicate
commands, history reload, and the close-vs-send race when Docker is available
in CI. Owner-layer unit tests independently cover early, late, cancelled,
rescheduled, wrong-participant and dependency-failure decisions.
