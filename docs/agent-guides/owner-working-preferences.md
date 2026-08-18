# Owner Working Preferences

This file records durable preferences explicitly confirmed by the repository owner in this project thread. It is not a claim that an agent can access unrelated or previous conversations. Future agents may use the current thread, repository history, accepted review feedback, and this file; they must not invent personal preferences from unavailable context.

## Confirmed preferences

1. **Tests are mandatory.** This is a capstone project, so every behavioral change includes proportionate unit, integration, contract, consistency/concurrency, and failure-path tests. Lack of existing tests is a reason to add them, not to skip them.
2. **Do not add explanatory production-code comments.** Follow the naming, structure, and documentation rules in `AGENTS.md` and `service-structure.md`. Capture business rationale in tests, Markdown, contracts, ADRs, and the data dictionary.
3. **Follow the established pattern before creating a new one.** Inspect neighboring features, module README, error/DTO conventions, tests, migration style, and `.github` templates first. A new abstraction or pattern needs a concrete gap and consistent application.
4. **Keep source trees feature-oriented and bounded.** Do not allow controllers, services, repositories, DTOs, or shared utilities to become dumping grounds. Split by domain feature and keep framework adapters outside domain logic.
5. **Make architecture decisions explicit.** Use the fixed service stack and transport responsibilities in ADR 0001. Do not silently substitute another broker, protocol, framework, module owner, or data store because it is familiar to the agent.
6. **Explain data for people, not through SQL comments.** Every PostgreSQL field is described in the Markdown data dictionary with what it stores, why it exists, and its consistency/security role.
7. **Respect project sources and team workflow.** Trace behavior to the capstone registration/WBS and follow `.github` issue/PR templates plus the Git collaboration guide.
8. **Prefer evidence over repeated trial and error.** After a failure, inspect the exact error and state, form a falsifiable hypothesis, and change one relevant variable. Re-running the same failing action without new evidence is not progress.
9. **Finish the whole affected workflow.** Code, DTO/event contract, migrations/data dictionary, configuration examples, tests, documentation, and review must agree before reporting completion.

## Collaboration behavior

- When ambiguity changes module ownership, technology, public API, consistency, privacy, or deployment, surface it before making broad changes. For smaller reversible details, follow the established pattern and continue.
- When the owner corrects an architectural decision, update the decision source first, then search the entire repository for stale terminology and conflicting guidance.
- Lead status/final reports with the outcome, evidence, material caveats, and next action. Do not hide failing tests or unverified assumptions behind a general completion statement.
- Preserve teammate work and unrelated changes. Never make the worktree look clean through destructive Git operations.

## Lessons captured from this architecture review

These are concrete causes of avoidable loops observed in this thread and the durable correction:

| Failure pattern | Why it happened | Required prevention |
| --- | --- | --- |
| Treating “REST for integration” as “remove WebSocket everywhere” | Client delivery and service-to-service transport were conflated | Write a transport responsibility matrix first: REST service queries, WebSocket client realtime, Kafka durable async, Redis ephemeral fan-out |
| Writing database descriptions as PostgreSQL comments | “Describe fields” was interpreted as executable metadata rather than documentation for the team | Confirm the requested artifact and keep field rationale in the Markdown data dictionary |
| Selecting/recommending a broker or module stack before the owner decision was fixed | A reasonable default was mistaken for a project decision | Record fixed technology choices in an ADR before propagating them; then repo-wide search for stale alternatives |
| Parsing a large document/tool result as if it were complete | Tool output truncation omitted a required table line and produced a false parser failure | Inspect truncation metadata and read large files in bounded, non-overlapping chunks before diagnosing content |
| Shell quoting failure around Markdown backticks | PowerShell interpreted an overly complex command string | Prefer smaller commands and single-quoted literal patterns; change strategy instead of repeatedly escaping the same command |

Do not preserve the historical mistake as a workaround. Preserve the root-cause rule and regression check.

## Preference update rule

Add a preference only when the owner states it explicitly or repeats it consistently across tasks. Keep one canonical instruction and link to detailed guides instead of copying the same rule into many files. Temporary task choices and guesses do not become permanent preferences. Product safety, security, source requirements, and accepted ADRs take precedence over convenience preferences.

The owner selected plain Node.js without NestJS for Journal/AI, Realtime, and Content/Notification. ADR 0003 fixes Express, strict TypeScript, explicit library composition, local-only `dotenv`, and Zod configuration validation.
