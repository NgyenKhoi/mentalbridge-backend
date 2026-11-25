# MB-363 approved online specialist discovery evidence

## Delivery status

The decision/actor-flow, Consultation owner capability, frontend/BFF consumer,
and parent-story verification subtasks are complete. Browser evidence uses
non-sensitive synthetic fixtures and is not evidence of a deployed environment.

## Confirmed decisions

- Consultation owns current approved-profile discovery, deterministic ranking,
  profile detail, and exact selectable-slot projection.
- A profile is discoverable only while it is `APPROVED` and has at least one
  currently selectable slot; losing the final slot removes it from list and
  makes detail return `SPECIALIST_NOT_DISCOVERABLE`.
- `FREE`, `PLUS`, and `PREMIUM` may browse. Booking remains an MB-378 command
  governed by MB-558 entitlement, credit, and reservation-cap policy.
- Ranking is lexicographic: minimized domain/pathway compatibility, language,
  availability, timezone, optional authoritative Premium rating tie-breaker,
  then specialist account ID.
- Rating never outranks a primary factor and remains neutral until a real
  rating capability exists.
- The static frontend fields for physical location, phone, external meeting,
  price, free introduction, credentials, unapproved title/specialty, and fake
  rating are prohibited replacement targets.
- Discovery responses contain no raw assessment answers or scores, safety
  evidence, Journal content, chat content, or clinical-suitability claim.
- Exact stale slots fail on owner reload/booking revalidation; the client never
  substitutes another specialist, time, or modality.

The full decision and user flow are in
[ADR 0027](adr/0027-approved-online-specialist-discovery.md)
(`MB-SPECIALIST-DISCOVERY-001`).

## Evidence by subtask

| Subtask | Evidence | Status |
| --- | --- | --- |
| Confirm ADR and user flow | ADR, policy, product blueprint, Consultation module specification, and traceability agree | Complete |
| Owner capability | OpenAPI 1.5.0, current-state owner query, Care context reducer, deterministic ranking/cursor, exact-slot handoff, and focused provider tests | Complete |
| Consumer flow | Same-origin BFF, real list/detail/filter/explanation/slot states, exact MB-378 slot handoff, and focused UI tests | Complete |
| Story verification | Matching cross-repository contract, PostgreSQL integration, fixture browser, concurrency, failure-path, and privacy inspection | Complete |

## Commands and results

Verification run on 2026-09-27 from the backend repository root:

| Command | Result |
| --- | --- |
| `git diff --check` | Pass, exit `0`; no whitespace errors |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev` | Pass, exit `0`; 790 tracked files and 8 changed files passed repository and paired-change policy |

Focused link command:

```powershell
$targets=@('docs/adr/0027-approved-online-specialist-discovery.md','docs/adr/0014-appointment-specialist-and-consultation-continuity.md','docs/adr/0017-product-scope-v2.md','docs/adr/0022-current-product-blueprint-amendments.md','docs/adr/0019-online-specialist-availability.md'); $targets | ForEach-Object { if(-not(Test-Path -LiteralPath $_)){ throw "Missing link target: $_" } }; 'Focused ADR link targets passed: 5 files.'
```

Result: pass, exit `0`; all five unique ADR link targets introduced or changed
by this subtask exist.

Focused decision-coverage command:

```powershell
$patterns=@('PracticeLocation','phone','external meeting link','price','credential','raw assessment','Journal content','chat content','PREMIUM','rating','FREE','PLUS','specialist-discovery-v1'); foreach($pattern in $patterns){if(-not(Select-String -LiteralPath docs\adr\0027-approved-online-specialist-discovery.md -SimpleMatch $pattern)){throw "Missing decision coverage: $pattern"}}; 'ADR decision coverage passed.'
```

Result: pass, exit `0`; ranking, entitlement, prohibited fields,
sensitive-content exclusions, and the policy version are explicitly covered.

The first repository-policy invocation used `pwsh`, which is not installed in
this Windows environment. A direct script invocation was then blocked by the
machine execution policy. Neither attempt changed state. The exact successful
command above uses the installed `powershell.exe` with a process-scoped
execution-policy bypass. An initial generic Markdown-link parser also rejected
an unrelated pre-existing angle-bracket link containing parentheses; the
focused link check above validates every link added or changed by this subtask.

## Owner capability evidence

Consultation implements authenticated list and detail reads at
`GET /api/v1/specialists` and
`GET /api/v1/specialists/{specialistAccountId}`. The query reads current
profile approval and current availability in one owner-database statement,
excludes active appointment holds, disabled video, withdrawn and too-soon
slots, and returns the exact slot ID/version consumed by MB-378.

No MB-363 migration was added. The capability deliberately reuses the
existing `specialist_profile`, support-area/language, `availability_slot`,
`appointment`, and `current_service_entitlement` owner tables. It does not
persist discovery results or minimized Care ranking context.

The optional Care call accepts one exact SupportEvaluation ID and reduces the
owned response in memory to closed support-area priorities. Dependency error,
wrong identity, or malformed closed values produce `UNAVAILABLE`/neutral
ranking. Raw answers, scores, safety evidence, Journal content, and chat
content are neither represented by the adapter DTO nor returned by discovery.

Verification run on 2026-09-28 from `consultation-service` unless stated:

| Command | Result |
| --- | --- |
| `mvn.cmd -q -DskipTests compile` | Pass, exit `0` |
| `mvn.cmd -q "-Dtest=ScreeningContextResolverTests,ConsultationOpenApiContractTests" test` | Pass, exit `0`; resolver fail-neutral and OpenAPI minimization checks |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q "-Dtest=DiscoveryFlowIntegrationTests,DiscoveryVideoEnabledIntegrationTests" test` | Pass, exit `0`; 7 tests, 0 failures/errors/skips |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q test` | Pass, exit `0`; 14 suites, 60 tests, 0 failures/errors/skips |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev` (repository root) | Pass, exit `0`; 790 tracked files and 21 changed files passed repository and paired-change policy |
| `git diff --check` (repository root) | Pass, exit `0`; no whitespace errors |

The owner integration cases cover approved-only visibility, suspension and
restoration reload, withdrawn/too-soon/held slots, disabled and enabled video,
FREE browsing, PLUS/PREMIUM booking-policy handoff, primary-factor ordering,
stable UUID ties and cursor paging, empty results, malformed filters, stale
cursor rejection, neutral Care fallback, and prohibited payload fields. All
data is synthetic and contains no real health content.

The first integration attempt accurately failed before application startup
because Docker Desktop was not running. After Docker was started, PostgreSQL
rejected the workstation alias `Asia/Saigon`; reruns use the equivalent IANA
zone `Asia/Ho_Chi_Minh` through process-scoped `JAVA_TOOL_OPTIONS`. The first
successful database run exposed and led to a fix for default-window cursor
hash instability; the focused integration suite and full module suite then
passed.

The repository-policy run temporarily isolated the user's pre-existing deletion
of `journal-ai-service/.env.example`, then restored it immediately after the
gate. That unrelated deletion remains unmodified and is excluded from MB-363.

## Parent Story verification and completion notes

Verification run on 2026-09-28 across the backend and frontend repositories:

| Command | Result |
| --- | --- |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q "-Dtest=DiscoveryFlowIntegrationTests,DiscoveryVideoEnabledIntegrationTests,ScreeningContextResolverTests,ConsultationOpenApiContractTests" test` | Pass with PostgreSQL through Testcontainers; 4 suites / 16 tests, 0 failures, errors, or skips |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q "-Dtest=AppointmentRequestIntegrationTests,AvailabilityFlowIntegrationTests" test` | Pass with PostgreSQL through Testcontainers; 2 suites / 13 tests, including concurrent slot hold, reservation-cap, suspension race, and overlapping publication cases |
| Cross-repository SHA-256 comparison of `contracts/openapi/consultation-service-v1.yaml` | Pass; backend and frontend snapshots match exactly |
| `npm.cmd test -- --run lib/consultation/consultation-validation.test.ts app/api/consultation/specialists/discovery-routes.test.ts features/specialist-discovery/components/SpecialistDiscovery.test.tsx` | Pass; 3 files / 19 tests, including empty, dependency-failure, and stale-slot UI states |
| `$env:PLAYWRIGHT_BASE_URL='http://127.0.0.1:3100'; npx.cmd playwright test tests/e2e/security-degradation.spec.ts --grep "specialists page uses approved online discovery" --workers=1 --reporter=line` | Pass; Chromium fixture journey 1/1 against a production standalone frontend and synthetic Identity/Care/Consultation fixture responses |
| `git diff --check` in both repositories | Pass; no whitespace errors |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-repository.ps1 -BaseSha origin/dev` | Blocked only by the unrelated user-owned deletion `journal-ai-service/.env.example` without a paired README change. MB-363 previously passed this gate with that deletion isolated; Subtask 4 did not alter or hide the user's deletion. |

Together, the owner tests prove approved-only visibility, suspension and
restoration reload, FREE/PLUS/PREMIUM boundaries, deterministic UUID rank ties,
empty results, held/stale slots, video disabled/enabled behavior, and neutral
Care failure. PREMIUM explicitly reports `ratingTieBreakerApplied=false`
because no authoritative rating capability exists; primary compatibility wins
before language, availability, and timezone, so rating cannot override it.

The cross-repository parser, component, and browser checks prove that the
consumer uses the exact returned slot identity and does not expose raw
assessment answers, Journal content, chat content, physical location, phone,
price, unsupported credentials/specialties, or external meeting links.

The first browser attempt completed its journey but its Playwright-managed
Windows web-server cleanup hung under local Node `v24.15.0`, so it is not
counted as evidence. The recorded passing command used the same production
standalone build with three manually managed hidden fixture processes; those
exact processes were stopped after the run and ports 3100, 3201, and 3202 were
confirmed closed.

## Zero-slot invariant correction on 2026-09-29

The review found that `DiscoveryService.load()` used `left join lateral` for
the selectable-slot projection. PostgreSQL therefore retained one profile row
with null slot columns when an approved specialist had no qualifying slot.
That contradicted the parent Story's approved-and-selectable catalogue
invariant and made zero-slot profiles visible after restoration or an active
appointment hold.

The owner query now uses an inner lateral join, so the same current-state SQL
requires both `APPROVED` profile state and at least one selectable slot. The
detail endpoint uses this same query and therefore returns
`SPECIALIST_NOT_DISCOVERABLE` when the last slot is withdrawn, held, outside
the window, or disabled by the video gate. The OpenAPI item schema now requires
at least one `selectableSlots` entry. Exact 60-minute duration and closed online
modalities remain enforced by the MB-362 owner validation and PostgreSQL
constraints rather than being redefined in discovery.

| Command | Result |
| --- | --- |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q "-Dtest=DiscoveryFlowIntegrationTests,DiscoveryVideoEnabledIntegrationTests,ConsultationOpenApiContractTests" test` | Pass; 3 suites / 14 tests, 0 failures, errors, or skips |
| `$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Ho_Chi_Minh'; mvn.cmd -q test` | Pass; 14 suites / 66 tests, 0 failures, errors, or skips |
| `git diff --check` | Pass; no whitespace errors |

The first focused run failed before application startup because Docker Desktop
was not running. Docker Desktop was started, `docker info` succeeded, and the
same focused command then passed. This failed environment attempt is not
counted as product evidence.

## Intentional deferrals

- Rating data and display remain absent until an authoritative rating story is
  implemented; MB-363 fixes only its optional Premium tie-break position.
- Deployed live-environment browser verification remains release/deployment
  evidence and is not fabricated by this Story.
