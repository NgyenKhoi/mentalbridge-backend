3.9.1 Start Chat with Specialist
Function Trigger
The function is triggered when an authenticated User navigates to /messages and selects an eligible Specialist conversation, or selects the chat action for a confirmed IN_APP_CHAT appointment. The system must derive the User identity from the trusted authenticated session and treat any conversation or appointment ID supplied by the client only as a resource selector.
Function Description
• Actors / Roles: Authenticated User; approved Specialist as the other authorized conversation participant
• Purpose: Allows a User to open the one consultation conversation associated with an eligible confirmed appointment, load the authorized message history, and join its live channel when the appointment is within its authoritative messaging window.
• Interface: Conversation list, selected-conversation state, Specialist identity and presence indicator, appointment/chat availability state, message-history area, pagination or progressive history loading, message composer, send action, reconnect state, empty state, and generic error state.
• Data Processing:
o Identify the current User from the trusted authenticated session.
o Resolve the selected conversation through its authoritative appointment and verify that the User and Specialist are the server-authorized participants.
o Verify current Specialist approval, appointment status, IN_APP_CHAT channel, scheduled start/end instants, and conversation status before permitting a live join.
o Create or retrieve exactly one conversation for the eligible appointment; do not create unrestricted direct or 24/7 Specialist messaging.
o Retrieve a bounded initial page of message history using stable cursor ordering and return only content authorized for the current participant.
o Establish an authenticated live subscription only while BR-45 permits joining; assign sender and conversation context on the server.
o Treat presence as ephemeral display information rather than authorization or proof that messaging is allowed.
o Transfer message submission to the separate Send Message function; Start Chat does not itself define message-content validation or persistence.
Screen Layout

The supplied reference screenshot defines the target chat workspace:
• The page header displays the workspace label “Tin nhắn”.
• The left panel displays conversation cards with avatar, participant name, latest-message preview, relative time, online indicator, and unread badge where applicable.
• The selected conversation is visually highlighted.
• The chat header displays the selected Specialist's identity and an availability/presence label such as “Đang hoạt động”.
• The conversation area displays authorized inbound and outbound message bubbles with timestamps and scrollable history.
• The input area displays “Nhập tin nhắn...” and a “Gửi” action; submission transfers to the separate Send Message function.
• When the appointment is not currently eligible for live chat, the history may remain read-only only when policy permits and MSG-039 is displayed near the disabled composer.
• Loading, empty, reconnecting, and generic failure states preserve the workspace structure and disclose no conversation belonging to another account.
Function Details
Data
FieldTypeSourceDisplay RuleConversation IDopaque identifierRealtime conversation recordDo not display; use only to select and subscribe to a server-authorized conversation.Appointment IDopaque identifierAuthoritative consultation appointmentDo not display unless another appointment-view function requires it; use to establish chat eligibility.User participantopaque account identifierTrusted session and server-generated participant setNever accept a client-supplied User ID as ownership proof.Specialist participantopaque account identifierConfirmed appointment and server-generated participant setDisplay only the reviewed Specialist name/avatar metadata returned for the authorized conversation.Appointment statusenumConsultation appointmentLive join requires the approved eligible state; a client label cannot override it.Appointment channelenumConsultation appointment snapshotLive chat requires IN_APP_CHAT; do not infer channel from the route or UI.Scheduled chat windowtimestamp rangeConsultation appointment snapshotCompare authoritative UTC instants on the server; format the window using the selected locale/timezone.Conversation statusenumRealtime conversation recordControl active or read-only presentation without exposing internal moderation/retention state.Message history itemauthorized message projectionRealtime message historyDisplay sender side, approved visible body or tombstone, and authoritative sent time; do not expose ciphertext, key data, moderation internals, or deleted content.History cursoropaque string or unavailableServer-generated history responseTreat as opaque and scoped to the authorized conversation; use bounded cursor pagination.Has more historybooleanServer-derivedControl progressive loading without guessing from the current message count.Presence statusenum/unknownEphemeral realtime presenceDisplay active/offline/unknown as a convenience only; never use it for authorization.Unread countnon-negative integerAuthorized conversation summaryDisplay the server-derived count; do not use fixed demonstration values as production truth.System Data
• Authenticated User ID and effective role obtained from trusted server-side authorization data.
• Selected opaque conversation or appointment identifier.
• Authoritative appointment participants, status, channel, scheduled start/end instants, timezone snapshot, and cancellation/reschedule state.
• Conversation participant snapshot, current conversation status, and read-only eligibility decision.
• Bounded message-history projection, stable cursor/tie-breaker data, and has-more indicator.
• Authenticated live-connection/session identifier, subscription authorization result, presence state, and reconnect cursor/version.
• Non-sensitive correlation, access, and connection audit metadata; raw chat bodies, encryption keys, access tokens, private object URLs, and sensitive health content are excluded from logs.
Validation & Business Rules
CodeRule DefinitionBR-05A Specialist may be publicly active only after Admin approval.BR-13An appointment is confirmed only after its slot and payment/credit eligibility conditions are satisfied.BR-14Only the authorized parties of an appointment/conversation may view its messages.BR-23Mock conversation and message data must not appear in production as real User data.BR-24Plan/feature entitlement must be checked on the server rather than trusted from localStorage.BR-25Effective roles and permissions must come from trusted server-side authorization data.BR-45A Specialist conversation is uniquely appointment-scoped; authorized participants may join and message only during the authoritative confirmed IN_APP_CHAT window, with later history read-only only when retention policy permits.Validation
SituationMessageHandlingNo valid authenticated session—Redirect to Sign In and return no conversation or message data.Conversation or appointment selector is missing/malformedMSG-030Do not open a conversation; show the generic error state and allow safe return.User is not an authorized participant—Return a non-disclosing denial/not-found response and reveal no participant, message, or record-count data.Specialist is not currently approved, appointment is not confirmed, or channel is not IN_APP_CHATMSG-039Do not create or join a live conversation; disable message entry and route the User to the appropriate separate appointment function when available.Current time is outside the authoritative appointment windowMSG-039Do not join the live channel or permit sending; show authorized history as read-only only when policy permits.Appointment is cancelled or rescheduled concurrentlyMSG-039Revoke or reject the live subscription, disable the composer, refresh eligibility, and do not accept stale client state.Conversation/history service cannot loadMSG-030Show the generic error state and allow a safe retry without displaying stale data as current.Live connection is unavailable after authorized history loadsMSG-021Keep authorized history visible, show reconnect state, and prevent the interface from implying that a message can currently be sent.Presence service is unavailable—Display presence as unknown; do not fail history loading or change authorization.No messages exist in an eligible conversation—Display an approved empty-conversation prompt and keep the composer available only while BR-45 permits.History cursor is invalid, expired, or belongs to another conversationMSG-030Reject the cursor, disclose no additional messages, and allow restart from the newest authorized page.Functionalities
Normal Flow
1. The authenticated User opens /messages or selects the chat action for a confirmed appointment.
2. The system validates the User's session, effective role, and server-side permission under BR-25.
3. The system derives the User identity from the trusted session and treats the supplied conversation or appointment ID only as a selector.
4. The system retrieves the authoritative appointment and verifies that the User and an approved Specialist are its participants under BR-05 and BR-14.
5. The system verifies the appointment status, reserved eligibility, IN_APP_CHAT channel, and scheduled window under BR-13, BR-24, and BR-45.
6. The system creates or retrieves the appointment's single conversation without enabling unrestricted direct Specialist messaging.
7. The system retrieves the newest bounded page of authorized history with a stable cursor and server-derived conversation summary.
8. The interface displays the selected Specialist, authorized message history, unread summary, and presence value or approved fallback.
9. If the appointment is currently inside its authoritative window, the system authorizes and establishes the live conversation subscription.
10. The interface enables the composer only after the live join is authorized; selecting “Gửi” transfers control to the separate Send Message function.
11. If more history exists, the User may request older pages using the server cursor without changing the conversation.
12. The User may select another eligible conversation or leave the page; Start Chat does not modify message content, appointment data, consent, or account data.
Current Frontend Flow
1. /messages defines three hard-coded conversations containing fixed names, emoji avatars, previews, relative times, unread counts, and online flags.
2. The page initializes selectedConv to conversation 1 and changes only that client-side value when a conversation card is selected.
3. The selected card highlight changes, but the chat header always displays “TS. Nguyễn Thị Lan” and the transcript always maps the same five hard-coded CHAT_MESSAGES, regardless of the selected conversation.
4. The mock list includes “Hỗ trợ MentalBridge” and Specialist conversations that are not tied to any appointment identifier or authoritative appointment window.
5. The textarea stores draft text only in local component state.
6. Pressing Enter without Shift prevents the default action but does not send or append a message; the “Gửi” button has no click handler.
7. No authenticated session guard, conversation API, persistent history, ownership/participant check, appointment/channel/window authorization, server pagination, WebSocket subscription, reconnect recovery, read receipt, or production presence source is implemented.
8. The Specialist discovery page links available Specialists only to appointment booking and provides no direct route that creates an authorized chat.
9. The Realtime Service specification and MongoDB shapes are documented, but the implementation tasks and versioned WebSocket conversation contracts are not present in the current repository.
Abnormal Cases
• Missing or expired session → redirect to Sign In and return no conversation data.
• Client-supplied User, role, participant, sender, or Specialist approval value → ignore it and use trusted server-side identity and authorization.
• Conversation belongs to another account → return a non-disclosing denial/not-found response and no participant/message metadata.
• No confirmed eligible IN_APP_CHAT appointment → display MSG-039 and do not create unrestricted direct chat.
• Join attempted before or after the appointment window → display MSG-039; keep only policy-authorized read-only history.
• Appointment cancelled or rescheduled while the page is open → revoke live access, disable the composer, display MSG-039, and refresh authoritative eligibility.
• History load fails → display MSG-030 and provide safe retry behavior.
• Live connection or reconnect fails → display MSG-021, preserve already authorized history, and prevent false sent/online state.
• Presence is unavailable → display unknown status without granting or removing access.
• Invalid/cross-conversation cursor → reject it, disclose no additional messages, and restart history safely.
• Duplicate conversation creation request → return the existing appointment-scoped conversation rather than creating another one.
Post-Conditions
• PC-01: The authenticated User has opened only a conversation for which they are an authorized appointment participant.
• PC-02: At most one conversation is resolved for the eligible confirmed IN_APP_CHAT appointment.
• PC-03: Authorized history is displayed using bounded stable pagination; live subscription exists only during the authoritative appointment window.
• PC-04: Starting or viewing the chat changes no message body, appointment, consent, health record, or account data; message submission remains a separate function.
• PC-05: On authentication, authorization, eligibility, dependency, or connection failure, no protected conversation content or participant information is disclosed and the interface fails closed.

