# MB-779 JWT account-status enforcement — DEV/TEST evidence

**Engineering baseline; pending Tech Lead and Security approval.** Identity checks account status locally. Care, Community and Consultation validate JWTs locally before calling Identity with separate service credentials; secrets must not be logged. ACTIVE is allowed; non-ACTIVE or unknown accounts return 401. An unavailable dependency, malformed response or open circuit returns 503. ACTIVE is not cached, there is no iat/revokedAt cutoff, and Feign does not retry.

Connect timeout is 1–250 ms and read timeout is 1–750 ms, with defaults of 250/750 ms and a combined maximum of 1000 ms. The circuit breaker uses a window of 10 calls, minimum 5 calls, 50% failure threshold, 5-second open state and one half-open call.

On code commit `29808d8023fd5c801eb9a4a0cc9476d25f9c68fc`, focused timeout/security tests in the three resource services passed (30 tests), and the Identity OpenAPI contract test passed (1 test). Local Docker E2E was not run. GitHub checks for the PR head provide the integration result.

**Capacity/SLO: NOT VERIFIED.** Availability of 99.9%, p95 at or below 100 ms, and p99 at or below 250 ms are verification targets, not measured results. No production approval is implied. MB-778 privacy policy approval is not implied. **BLOCKED FOR MERGE** pending Tech Lead/Security approval and re-review.
