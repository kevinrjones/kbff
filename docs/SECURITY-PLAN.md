### Security Remediation Plan

Derived from: `docs/REVIEW.md`
Last updated: `2026-05-11`

### Purpose & Inputs

- [x] Use this plan as the implementation playbook for the security findings documented in `docs/REVIEW.md`.
- [x] Keep work severity-prioritized: High findings first, then Medium findings.
- [x] Track implementation progress by checking off tasks directly in this document.
- [x] Keep all changes within current architecture boundaries:
  - `domain/model`
  - `domain/service`
  - `presentation/auth`
  - `presentation/route`
  - `src/test/...`

### Execution Order & Milestones

- [x] Milestone 1 (High): Workstream A + Workstream B complete and verified.
- [x] Milestone 2 (Medium): Workstream C + Workstream D complete and verified.
- [x] Milestone 3 (Release Gate): all findings mapped, validated, and ready for merge.

Dependencies:

- [x] Workstream A (Redirect hardening) should complete before finalizing auth callback behavior checks in release gates.
- [x] Workstream B (Session-bound CSRF) depends on session model updates and should be complete before proxy/logout security sign-off.
- [x] Workstream C (Logging sanitization) should run after A/B to validate final auth/proxy log output.
- [x] Workstream D (TLS guardrails) should be complete before production readiness sign-off.

### Workstream A: Open Redirect Hardening (High)

#### Objective
- [x] Prevent post-login redirects from leaving trusted/local destinations.

#### Affected files
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutes.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffRouteHelpers.kt`
- [x] `src/test/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutesTest.kt`

#### Implementation steps
- [x] In `KbffRouteHelpers.kt`, add a dedicated validator for post-login redirect targets used by auth routes.
- [x] Enforce policy that only app-relative paths are accepted by default (must start with `/`, must not start with `//`, and must not contain a scheme such as `http:`/`https:`).
- [x] Keep explicit guard that callback endpoints cannot be used as `returnUrl` destinations to avoid callback loops.
- [x] Canonicalize accepted values before session persistence (strip fragments, normalize empty/blank to safe default `/`).
- [x] In `KbffAuthRoutes.kt` login entrypoint, validate `returnUrl` immediately and store only sanitized value in session.
- [x] In `KbffAuthRoutes.kt` callback completion path, redirect only to the sanitized session value and use `/` fallback when absent/invalid.
- [x] Add warning-level audit log for rejected `returnUrl` values without echoing untrusted full URL payload.

#### Test updates
- [x] In `KbffAuthRoutesTest.kt`, add positive case: `returnUrl=/dashboard` is preserved and used after successful callback.
- [x] Add negative case: absolute external URL (`https://evil.example`) is rejected/sanitized and final redirect is `/`.
- [x] Add negative case: protocol-relative URL (`//evil.example/path`) is rejected/sanitized.
- [x] Add negative case: callback URL values (for example `/bff/callback` variants) are rejected to prevent loop behavior.
- [x] Add robustness case: blank/malformed `returnUrl` is normalized to `/`.
- [x] Manual verification: execute full login with malicious `returnUrl` inputs and confirm browser never leaves trusted origin.

#### Done criteria
- [x] External untrusted redirect attempts cannot be used to leave trusted origin after authentication.
- [x] Existing auth route behavior remains correct for allowed local paths.
- [x] Automated route tests prove both accepted local redirects and rejected malicious variants.

### Workstream B: Session-Bound CSRF Validation (High)

#### Objective
- [x] Replace header-presence checks with per-session CSRF token validation.

#### Affected files
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/auth/KbffSecurity.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/domain/model/KbffSession.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutes.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffProxyRoutes.kt`
- [x] `src/test/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutesTest.kt`
- [x] `src/test/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffProxyRoutesTest.kt`

#### Implementation steps
- [x] In `KbffSession.kt`, add a dedicated `csrfToken` field for server-side expected token storage.
- [x] In login/callback session establishment (`KbffAuthRoutes.kt`), generate cryptographically strong CSRF token and persist in session.
- [x] Keep token generation centralized in `KbffSecurity.kt` (or shared helper) to avoid inconsistent token formats.
- [x] Update `verifyCsrfToken(...)` in `KbffSecurity.kt` to require both header presence and exact equality to `session.csrfToken`.
- [x] Use constant-time comparison for token matching semantics.
- [x] Continue enforcing CSRF checks on `POST /bff/logout` and non-GET proxy requests in `KbffProxyRoutes.kt`.
- [x] On logout/session clear, remove token with session teardown; on new authenticated session, issue a fresh CSRF token.
- [x] Define explicit failure response contract (`403 Forbidden`) for missing, mismatched, or stale CSRF tokens.

#### Test updates
- [x] In `KbffAuthRoutesTest.kt`, add negative logout test: missing CSRF header returns `403`.
- [x] Add negative logout test: incorrect CSRF header value returns `403`.
- [x] Add positive logout test: matching session-bound CSRF token returns success and clears session.
- [x] In `KbffProxyRoutesTest.kt`, add negative non-GET proxy tests for missing and mismatched CSRF token (`403`).
- [x] Add positive non-GET proxy test for valid CSRF token acceptance.
- [x] Add session lifecycle test ensuring CSRF token changes across new authenticated sessions.
- [x] Manual verification: capture browser/API calls to ensure client echoes session-bound token and invalid replay is rejected.

#### Done criteria
- [x] State-changing routes reject requests without matching session-bound CSRF token.
- [x] Session lifecycle maintains correct CSRF rotation and clearing semantics.
- [x] Automated tests cover both positive and negative CSRF scenarios for auth and proxy routes.

### Workstream C: Sensitive Logging Reduction (Medium)

#### Objective
- [x] Eliminate or minimize logging of session/token security material.

#### Affected files
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutes.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/auth/KbffSecurity.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcService.kt`

#### Implementation steps
- [x] In `KbffAuthRoutes.kt`, replace session object debug logs with minimal event-style logs (for example: auth stage, correlation id/request id, and result).
- [x] In `KbffSecurity.kt`, remove direct session object dumps from helper logs and keep only non-sensitive session metadata.
- [x] In `OidcService.kt`, downgrade token-related logging from `INFO` to `DEBUG`.
- [x] Remove masked token fingerprint output from normal execution paths; keep optional diagnostics gated behind explicit debug and non-production usage.
- [x] Standardize sensitive logging policy: never log `access_token`, `id_token`, `refresh_token`, `state`, `nonce`, `codeVerifier`, or CSRF token values.

#### Test updates
- [x] Add/extend tests around `OidcService.logRetrievedTokens` behavior to ensure token values are not emitted at `INFO` level.
- [x] Add targeted regression test(s) for auth/security route flows confirming session objects are not logged verbatim.
- [x] Add manual verification checklist: run login + callback + proxy request and inspect logs for prohibited fields (`access_token`, `refresh_token`, `id_token`, `state`, `nonce`, `codeVerifier`, CSRF token).

#### Done criteria
- [x] Logs from auth/security/token flows do not expose session secrets or token fingerprints in normal operation.
- [x] Logging output remains operationally useful without containing secret material.

### Workstream D: `sslTrustAll` Production Guardrails (Medium)

#### Objective
- [x] Prevent insecure TLS trust override from being used in production contexts.

#### Affected files
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/domain/model/KbffConfiguration.kt`
- [x] `src/main/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcService.kt`
- [x] `src/test/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcServiceTest.kt`

#### Implementation steps
- [x] In `KbffConfiguration.kt`, define explicit environment gate for `sslTrustAll` (allowed only for local development/testing).
- [x] Add startup-time validation that fails fast when `sslTrustAll=true` with production-like environment/profile.
- [x] Ensure `OidcService.kt` only constructs permissive retriever mode when gate conditions are satisfied.
- [x] Emit a clear warning log when `sslTrustAll=true` is used in allowed non-production modes.
- [x] Document required config behavior and defaults so operations teams can safely deploy.

#### Test updates
- [x] In `OidcServiceTest.kt`, add negative config test: production environment + `sslTrustAll=true` fails validation/startup.
- [x] Add positive config test: development/test environment + `sslTrustAll=true` is allowed with explicit warning path.
- [x] Add baseline test: production environment + `sslTrustAll=false` remains valid.

#### Done criteria
- [x] Application cannot start with insecure TLS trust-all mode in production profile.
- [x] Configuration behavior is deterministic and covered by automated tests for allowed/denied combinations.

### Release Gate Checklist

- [x] Workstream A complete: redirect allow/deny tests pass and manual malicious redirect checks are signed off.
- [x] Workstream B complete: CSRF token binding tests pass for auth + proxy state-changing routes.
- [x] Workstream C complete: log non-leakage tests/checks pass and security-sensitive fields are absent from normal logs.
- [x] Workstream D complete: config guardrail tests pass for production and dev/test matrix.
- [x] Full targeted test suite passes (`KbffAuthRoutesTest`, `KbffProxyRoutesTest`, `OidcServiceTest`, plus any new focused tests).
- [x] `docs/REVIEW.md` findings are fully traceable to this plan and each has implemented evidence.

### Traceability Matrix

- [x] `REVIEW: High / Open redirect` -> `SECURITY-PLAN: Workstream A` -> evidence: auth route redirect policy tests + manual login redirect validation.
- [x] `REVIEW: High / CSRF header presence only` -> `SECURITY-PLAN: Workstream B` -> evidence: logout/proxy CSRF positive+negative tests.
- [x] `REVIEW: Medium / Sensitive logging` -> `SECURITY-PLAN: Workstream C` -> evidence: logging regression checks and token/session log sanitization assertions.
- [x] `REVIEW: Medium / sslTrustAll risk` -> `SECURITY-PLAN: Workstream D` -> evidence: environment guardrail tests for allow/deny matrix.