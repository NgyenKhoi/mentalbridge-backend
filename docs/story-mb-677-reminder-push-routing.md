# MB-677 reminder push routing contract and acceptance

## Scope and owner confirmation

Content/Notification owns native push scheduling and delivery. Consultation
remains authoritative for appointment identity, version, owner, status,
scheduled start and modality. Care remains authoritative for SupportPlan action
schedules and occurrences.

The implemented appointment slice uses the accepted
`consultation.appointment.status-changed` v2 event and the implemented
`GET /internal/v1/appointments/{appointmentId}/notification-eligibility`
owner check. The event supplies the exact confirmed version and UTC start; the
owner check closes cancellation and reschedule races immediately before a
native provider submission.

SupportPlan activity push is deliberately deferred. The accepted
`care.support-plan.action-occurrence-changed` v2 event carries occurrence state
and bounded owner evidence, but it does not carry the scheduled reminder
instant, schedule timezone, or the owner's reminder choice. Content must not
derive those facts by polling Care's private state. Care must first publish an
additive approved event contract containing the minimized schedule decision.

No frontend file is part of MB-677. MB-679 remains responsible for mobile
permission prompts and safe action handling; Android real-device execution
remains `NOT_RUN` until the MB-666 staging milestone.

## Acceptance states

| State                                                                                         | What the client may show after a compatible consumer exists      | What it must not show                                                                | Business rule and source                                                                                                          |
| --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| Exact confirmed appointment reaches the 60-minute target outside quiet hours                  | One generic appointment reminder linked by opaque appointment ID | Specialist identity, consultation reason, brief, chat, health or entitlement content | Consultation event v2; `MB-APPOINTMENT-EMAIL-REMINDER-001`; `MB-NATIVE-PUSH-DELIVERY-001`                                         |
| Target falls inside quiet hours and quiet hours end before the appointment starts             | The same reminder only after quiet hours end                     | Early delivery or delivery inside quiet hours                                        | Quiet hours take precedence; the reminder is deferred, never moved earlier                                                        |
| Quiet hours do not end before appointment start                                               | No native push                                                   | A quiet-hour override or post-start reminder                                         | Quiet hours take precedence and the intent expires at appointment start                                                           |
| Appointment is cancelled, rejected, expired, rescheduled to a newer version, started or ended | No stale reminder action                                         | A push for an obsolete version or cancelled target                                   | Current Consultation truth must match owner, exact version, modality and future start                                             |
| The same event is replayed or the owner has multiple active devices                           | One durable inbox fact and at most one attempt per fact/device   | Duplicate inbox facts or duplicate attempts for one device                           | Logical source identity is appointment plus version; database uniqueness remains authoritative                                    |
| Consultation, preference truth, or notification persistence is temporarily unavailable        | No provider submission; bounded retry may occur before start     | Assumed eligibility, a dropped first failure, or a successful-delivery claim         | Dependency uncertainty fails closed; an exhausted processing lease records a terminal failure instant and cannot be claimed again |
| Expo accepts a ticket or later returns a receipt                                              | Acceptance/receipt evidence only                                 | A claim that the user saw or physically received the notification                    | `MB-NATIVE-PUSH-DELIVERY-001`                                                                                                     |
| SupportPlan occurrence event v2 is received                                                   | No activity push in this slice                                   | A reminder time inferred from occurrence state or private Care polling               | Deferred until Care publishes approved reminder instant, timezone and reminder-choice facts                                       |

## Privacy and routing

The durable fact and provider payload use generic Vietnamese copy, the
notification UUID, and the closed `OPEN_APPOINTMENT` action with an opaque
appointment UUID. The app must authenticate and reauthorize the current
appointment when the action opens. No assessment answers, Journal or emotion
content, safety state, consultation content, specialist name, chat body, plan
details, tokens or provider responses enter the push payload.

## Verification plan

- Contract tests cover the closed appointment action and safe href.
- Unit tests cover replay, cancellation/reschedule, post-I/O cutoff and
  quiet-hour precedence, exact appointment identity, retry/dependency failure
  and privacy-minimized payloads.
- PostgreSQL integration tests cover one durable fact per appointment/version
  and one push attempt per active device, including terminal reconciliation of
  a crashed final processing lease.
- Android real-device delivery is authored downstream and remains `NOT_RUN`
  until MB-666.
