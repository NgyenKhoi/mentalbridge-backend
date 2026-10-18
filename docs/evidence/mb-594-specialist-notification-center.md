# MB-594 specialist notification center evidence

Content/Notification keeps the shared durable owner-scoped inbox while adding an explicit `SPECIALIST` preference scope. Identity account lifecycle projection initializes bounded specialist defaults, and both validation and a database constraint prevent specialist accounts from enabling Journal, emotion, streak, screening, Community, wellbeing-digest, or resource-reminder categories.

Consultation appointment lifecycle events now include only the assigned specialist account ID in addition to the existing operational facts. The consumer materializes minimized appointment copy with an approved `/specialist/appointments` action. `(recipient_id, source, appointment_id:aggregate_version)` provides replay-safe deduplication; preference-disabled facts are retained as `CANCELLED` and never appear in the inbox.

No Journal, assessment, ConsultationBrief, SessionSummary, or chat body crosses the event or notification boundary. The existing authenticated inbox endpoints remain owner-scoped for list, read, mark-all-read, and delete operations.
