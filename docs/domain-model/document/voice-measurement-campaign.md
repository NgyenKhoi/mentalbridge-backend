# Journal/AI measurement campaign — MB-862 proposal implementation

Owner: Journal/AI. PO design approval: Jira MB-862 #10461, source/tests only.
This is a read-only logical model; migration `020_voice_measurement_campaigns.cjs`
is the executable schema authority. No live campaign is created by this file or
the migration. Merge after PR #299 per owner decision; integrate links into the
shared Mongo model/dictionary only after that PR is resolved.

Collection: `ai_voice_measurement_campaigns`, optional operational fence separate
from product quota and financial ledgers. One campaign has an immutable UUID,
canonical configuration digest, approved window, caps, fixed session allocation,
at most two reservations and creation/update/expiry instants. No owner IDs,
audio, transcript, prompts, reply text, credentials or raw approval payloads.

Reservations reference Journal-owned Voice sessions by UUID. Each records the
full allocated turns/time/TTS budget, creation and authoritative release time,
one-shot worker claim and bounded turn/reply reservation identities/counts.
Admission consumes the entire allocation even if no provider call succeeds.
Release frees concurrency only, never spend. Unknown teardown holds concurrency;
existing global capacity reconciliation quarantines unresolved media after
session retention expiry. Campaign metadata expires 30 days from record creation;
an expired window cannot authorize work even before TTL cleanup runs.

All campaign admission changes share the existing Mongo transaction with product
quota/session/global capacity. No transaction includes an owner/provider request.
Unique multikey session index prevents one session belonging to multiple campaigns;
empty reservation arrays are excluded. TTL is metadata deletion, not a retry or
refund authority. Missing records fail closed for configured measurement mode.
