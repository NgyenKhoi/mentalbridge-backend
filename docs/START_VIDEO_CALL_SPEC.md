# 3.9.3 Start Video Call

## Function Trigger

The function is triggered when an authenticated User or approved Specialist selects **“Tham gia”** or **“Vào phòng tư vấn”** for an appointment intended to use `IN_APP_VIDEO`. The system must validate the trusted authenticated session, the selected appointment, and the project-wide video enablement gate before displaying device checks or issuing any room authorization.

## Function Description

- **Actors / Roles:** Authenticated User; approved Specialist when they are the other authorized appointment participant
- **Purpose:** Allows an authorized appointment participant to review session details, preview and configure local camera/microphone state, check basic connection readiness, and request entry to the appointment's secure video room after all approved eligibility conditions are satisfied.
- **Interface:** Pre-call modal or page, close action, appointment/Specialist summary, local camera preview, microphone toggle, camera toggle, device-readiness list, network-readiness state, privacy notice, **“Để sau”** action, and **“Vào phòng tư vấn”** action.
- **Data Processing:**
  - Identify the current actor and effective role from the trusted authenticated session.
  - Treat the client-supplied appointment ID only as a selector and verify that the actor is an authoritative participant.
  - Verify that `IN_APP_VIDEO` has been enabled by an accepted call/signalling/provider/security contract and ADR under BR-46.
  - Retrieve the authoritative appointment status, channel, participants, scheduled start/end instants, timezone snapshot, and current cancellation/reschedule state.
  - Request local camera/microphone permission only for the visible pre-call check and keep the device preview within the approved media-handling boundary.
  - Derive device readiness from actual permission/device state rather than fixed demonstration values.
  - Derive network readiness using the approved call contract after it exists; do not invent a provider-specific rule before approval.
  - Request a short-lived, appointment-scoped room authorization only after server-side eligibility succeeds.
  - Transfer control to the separately defined live video-session function; do not expose provider secrets, reusable room credentials, external meeting links, or another appointment's room information.

**Current implementation note:** The supplied pre-call screen is a target reference, not an implemented frontend flow. The current project explicitly reserves `IN_APP_VIDEO` as future intent and keeps it disabled until a later contract/ADR defines signalling, provider choice, room credentials, participant presence, recording policy, failure fallback, and completion evidence.

## Screen Layout

The supplied reference screenshot defines the target pre-call workspace:

- The header displays **“START VIDEO CALL”**, **“Sẵn sàng cho phiên tư vấn?”**, the instruction **“Kiểm tra thiết bị trước khi vào phòng cùng chuyên gia.”**, and a close action.
- The main preview panel displays the local camera preview or an approved initials/avatar fallback, plus microphone and camera controls.
- The appointment summary displays the Specialist avatar/name and localized scheduled date/time.
- The **“KIỂM TRA THIẾT BỊ”** panel displays microphone, camera, and network readiness using actual current state.
- The privacy notice explains the approved private-session behavior without claiming encryption, recording prohibition, or provider guarantees that have not yet been accepted in the video contract.
- **“Để sau”** closes the pre-call view without changing the appointment.
- **“Vào phòng tư vấn”** is enabled only when the feature, actor, appointment window, device requirements, and room-authorization request are valid.
- Loading, permission-denied, unsupported-feature, outside-window, network-failure, and generic error states disclose no room credential or other participant's data.

## Function Details

### Data

| Field | Type | Source | Display Rule |
|---|---|---|---|
| Appointment ID | opaque identifier | Selected appointment | Do not display; use only as a selector and authorize it against the trusted session actor. |
| Participant identity | opaque account identifier | Trusted session and authoritative appointment | Never accept actor/role/participant identity from the client as proof of access. |
| Specialist display name/avatar | reviewed string/image or fallback | Approved Specialist profile linked to the appointment | Display only the minimal reviewed identity required for the scheduled session. |
| Appointment status | enum | Consultation appointment | Entry requires the approved eligible state; client-side status text is not authoritative. |
| Appointment channel | enum | Consultation appointment snapshot | Must equal `IN_APP_VIDEO` after BR-46 has been satisfied. |
| Scheduled start/end | timestamp range | Consultation appointment snapshot | Evaluate eligibility using authoritative instants; format for the selected locale/timezone. |
| Video feature state | enum/boolean | Approved server configuration and accepted contract version | When disabled or unapproved, show MSG-040 and issue no room authorization. |
| Microphone permission | enum | Browser/device permission state | Display ready, denied, unavailable, or prompt state; do not infer readiness from a mock checkmark. |
| Camera permission | enum | Browser/device permission state | Display ready, denied, unavailable, or prompt state; use a safe preview fallback when unavailable. |
| Microphone/camera enabled | boolean | Local pre-call controls | Reflect the participant's current local selection; do not treat mute state as device permission. |
| Selected device | local opaque device reference or default | Browser/device selection | Keep local unless the approved call contract requires a minimized value; do not persist raw device labels unnecessarily. |
| Network readiness | enum/unknown | Approved call-readiness mechanism | Display ready, unstable, failed, or unknown without promising call quality. |
| Room authorization | short-lived opaque credential | Approved server/provider boundary | Never display or log; scope to actor, appointment, room, role, and expiry after the contract is accepted. |
| Pre-call UI state | enum | Client interaction state | Control checking, ready, blocked, joining, and failure presentation without creating appointment truth. |

### System Data

- Authenticated actor ID and effective role from trusted server-side authorization.
- Selected appointment ID, authoritative User/Specialist participants, Specialist approval state, appointment status/channel, scheduled interval, timezone snapshot, and cancellation/reschedule version.
- Approved video-feature enablement state plus accepted call/signalling/provider/security contract and policy version.
- Ephemeral local permission, device-enabled, preview, and network-readiness state.
- Short-lived appointment-scoped room authorization, expiry, correlation ID, and safe join-attempt outcome only after video enablement.
- Non-sensitive access/security audit metadata; camera frames, microphone audio, provider secrets, reusable room credentials, access tokens, and sensitive consultation content are excluded from logs.

### Validation & Business Rules

| Code | Rule Definition |
|---|---|
| BR-05 | A Specialist may be publicly active only after Admin approval. |
| BR-13 | An appointment is confirmed only after its slot and payment/credit eligibility conditions are satisfied. |
| BR-23 | Mock appointment, device, and video-room data must not appear in production as real state. |
| BR-24 | Plan/feature entitlement must be checked on the server rather than trusted from `localStorage`. |
| BR-25 | Effective roles and permissions must come from trusted server-side authorization data. |
| BR-46 | `IN_APP_VIDEO` remains disabled until its contract/ADR is accepted; afterward only authorized participants of a confirmed video appointment may request room entry during the permitted window. |

### Validation

| Situation | Message | Handling |
|---|---|---|
| No valid authenticated session | — | Redirect to Sign In and return no appointment, device-check, or room data. |
| Video contract/ADR is not accepted or feature remains disabled | MSG-040 | Do not request media permission or room authorization; route the actor to the supported consultation channel where appropriate. |
| Appointment selector is missing or malformed | MSG-030 | Do not show session details or request room authorization; allow safe return. |
| Actor is not an authoritative appointment participant | — | Return a non-disclosing denial/not-found response and reveal no session or participant data. |
| Specialist is not approved, appointment is not confirmed, or channel is not `IN_APP_VIDEO` | MSG-042 | Disable room entry and route to the appropriate separate appointment function when available. |
| Current time is outside the approved room-entry window | MSG-042 | Disable **“Vào phòng tư vấn”** and do not issue a room credential. |
| Camera or microphone permission is denied/unavailable | MSG-041 | Show the affected device state, keep room entry disabled when the approved contract requires it, and allow permission/device retry. |
| Network readiness check is unstable or fails | MSG-043 | Keep entry disabled when required by the approved contract and allow a safe recheck. |
| Appointment is cancelled or rescheduled during pre-call checks | MSG-042 | Invalidate readiness and any issued authorization, refresh appointment data, and do not enter the stale room. |
| Room authorization request fails or expires before use | MSG-030 | Clear the credential, remain on the pre-call view, and allow one safe reauthorization after current eligibility is rechecked. |
| Actor selects close or **“Để sau”** | — | Stop the local preview, release device access used by the pre-call view, and return without changing the appointment. |

## Functionalities

### Normal Flow

1. The authenticated User or Specialist selects the join action for a scheduled video appointment.
2. The system validates the actor's session, effective role, and server-side permission under BR-25.
3. The system checks the project-wide `IN_APP_VIDEO` enablement gate under BR-46 before requesting device permission.
4. After video has been formally enabled, the system derives the actor from the trusted session and retrieves the selected authoritative appointment.
5. The system verifies that the actor is a participant, the Specialist is approved, the appointment is confirmed and entitled, and the channel is `IN_APP_VIDEO` under BR-05, BR-13, and BR-24.
6. The system verifies the current appointment version and permitted entry window using authoritative server time.
7. The pre-call view displays the Specialist and localized appointment summary without exposing internal room data.
8. The actor permits the visible local device check; the interface previews the selected camera or approved fallback and reports actual microphone/camera state.
9. The system performs the network-readiness check defined by the accepted call contract and displays ready, unstable, failed, or unknown state.
10. The actor may toggle the local microphone/camera state and repeat device checks.
11. When required readiness conditions are satisfied, the actor selects **“Vào phòng tư vấn”**.
12. The server revalidates participant, appointment status/channel/window, feature enablement, and current appointment version.
13. The server issues a short-lived appointment-scoped room authorization through the approved provider boundary.
14. The system transfers control to the separately defined live video-session function and consumes or expires the entry authorization according to the accepted contract.

### Current Frontend Flow

1. `/appointments` defines local `UPCOMING` and `PAST` arrays with fixed demonstration appointments.
2. The first `UPCOMING` item is hard-coded as a confirmed **“Video call”** with **“TS. Nguyễn Thị Lan”**, date `2026-08-15`, and time `10:00 - 11:00`; it remains in the upcoming list independently of authoritative server time.
3. Every upcoming appointment renders a **“Tham gia”** button, but the button has no handler, route, modal, or eligibility logic.
4. The supplied **“START VIDEO CALL”** pre-call screen is not implemented in the current frontend.
5. No camera/microphone permission request, device enumeration, local preview, network-readiness check, room-authorization request, signalling connection, or video provider integration exists.
6. No authenticated session guard, participant ownership check, Specialist approval check, entitlement check, appointment status/channel/window validation, cancellation/reschedule refresh, or server-time decision is implemented on the page.
7. The Specialist workspace displays **“Vào phòng tư vấn”** as demonstration dashboard copy but does not implement a production video-room transition.
8. Approved project documents explicitly keep `IN_APP_VIDEO` disabled; no accepted video contract, room/signalling implementation, provider choice, recording policy, failure fallback, or completion-evidence flow exists.

### Abnormal Cases

- Missing or expired session → redirect to Sign In and return no session or room data.
- Video feature remains unapproved/disabled → display MSG-040; do not request device permissions or create room authorization.
- Client-supplied actor, role, participant, appointment state, channel, or schedule → ignore it and use trusted server-side values.
- Appointment belongs to another actor → return a non-disclosing denial/not-found response.
- Appointment is pending, cancelled, completed, rescheduled, or not `IN_APP_VIDEO` → display MSG-042 and disable entry.
- Entry requested outside the approved window → display MSG-042 and issue no room authorization.
- Camera/microphone missing or permission denied → display MSG-041 and allow safe device/permission retry.
- Network readiness is unstable → display MSG-043 and allow recheck without promising call quality.
- Appointment changes concurrently → invalidate local readiness and room authorization, display MSG-042, and refresh.
- Room authorization fails/expires → display MSG-030, clear it, and revalidate before retry.
- Actor closes or defers → stop the preview/release local device access and leave appointment state unchanged.

## Post-Conditions

- **PC-01:** While `IN_APP_VIDEO` is unapproved or disabled, no device permission, signalling connection, room credential, or live session is created.
- **PC-02:** After formal enablement, only an authorized participant of the eligible confirmed video appointment can receive a short-lived appointment-scoped room authorization.
- **PC-03:** The pre-call view reflects actual local device and approved network-readiness state rather than fixed demonstration values.
- **PC-04:** Starting, cancelling, or deferring the pre-call check does not change the appointment, credit, subscription, consent, health record, or account data.
- **PC-05:** On authentication, authorization, eligibility, device, network, dependency, or concurrency failure, no reusable credential, provider secret, other participant's room information, or sensitive media content is disclosed.

## BR and MSG Registry Reference

All codes below are defined once in the MentalBridge Master Registry:

| Code | Type | Usage in this function |
|---|---|---|
| BR-05 | Business Rule | Requires authoritative Specialist approval. |
| BR-13 | Business Rule | Requires a valid confirmed appointment. |
| BR-23 | Business Rule | Prevents mock video readiness and room state from appearing as production truth. |
| BR-24 | Business Rule | Requires server-side entitlement decisions. |
| BR-25 | Business Rule | Requires trusted server-side role and permission data. |
| BR-46 | Business Rule | Enforces the project-wide video enablement gate and appointment-scoped room access. |
| MSG-030 | Generic error | Handles safe selector, dependency, and room-authorization failures. |
| MSG-040 | Availability notice | Explains that the video feature is not yet enabled. |
| MSG-041 | Device error | Handles camera/microphone permission or availability failure. |
| MSG-042 | Appointment/window notice | Handles ineligible appointment state or entry timing. |
| MSG-043 | Network readiness error | Handles an unstable/failed pre-call connection check. |
