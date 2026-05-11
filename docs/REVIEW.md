### Security Code Review: `kbff`

Review date: `2026-05-10`

#### Summary

The project has a strong baseline for a Ktor BFF (OIDC state/nonce/PKCE, explicit security headers, server-side session model, and proxy target allowlisting). The highest-risk issues are around redirect and CSRF semantics: user-controlled post-login redirects are not constrained to local/trusted URLs, and CSRF validation checks only for header presence rather than binding to session/user intent. These should be prioritized before production use. Additional medium findings relate to sensitive logging and permissive transport options.

#### Findings

##### High: Open redirect after authentication
- **Location**: `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutes.kt` (`/bff/login` + callback redirect), `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffRouteHelpers.kt` (`normalizeReturnUrl`)
- **Risk / exploit scenario**: `returnUrl` can be supplied as an absolute external URL. `normalizeReturnUrl` only rejects callback URLs, not off-origin destinations. An attacker can craft a login link such as `/bff/login?returnUrl=https://evil.example/phish`; after successful OIDC auth, user is redirected off-site with trusted app context.
- **Recommended fix**:
  - Allow only relative URLs, or absolute URLs matching a strict allowlist of host/scheme.
  - Canonicalize and validate redirect target before storing in session.
  - Fall back to `/` (or configured safe default) when invalid.
- **Verification approach**:
  - Add route tests asserting external absolute `returnUrl` values are rejected/sanitized.
  - Manual check: perform login flow with malicious `returnUrl` and confirm redirect never leaves trusted origin.

##### High: CSRF check validates header presence only (no token binding)
- **Location**: `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/auth/KbffSecurity.kt` (`verifyCsrfToken`)
- **Risk / exploit scenario**: For state-changing routes (`/bff/logout`, proxied non-GET), validation only requires a non-empty header value. Any script running in-origin (or any client that can send requests to BFF) can send arbitrary header values and bypass intended CSRF token semantics.
- **Recommended fix**:
  - Generate per-session CSRF secret and store it server-side/in session.
  - Compare request header token to expected session token (constant-time comparison).
  - Rotate on login/session regeneration and clear on logout.
- **Verification approach**:
  - Add negative tests: missing header, wrong token, stale token all return `403`.
  - Add positive tests: valid per-session token accepted.

##### Medium: Sensitive session/token data can leak through logs
- **Location**:
  - `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutes.kt` (`logger.debug("Setting initial session: {}", initialSession)`)
  - `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/auth/KbffSecurity.kt` (`logger.debug("Getting  session: {}", session)`)
  - `src/main/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcService.kt` (`logRetrievedTokens`)
- **Risk / exploit scenario**: Logging full session objects may expose security artifacts (state/nonce/code verifier and later tokens depending on call site). `logRetrievedTokens` masks values, which is better than raw logging, but still exposes token fingerprints at `INFO` level in centralized logs.
- **Recommended fix**:
  - Avoid logging session objects directly; log only minimal identifiers (session id/request id).
  - Downgrade token-related logs to `DEBUG` and avoid even masked token output in normal production paths.
  - Add log redaction policy tests or structured logging rules.
- **Verification approach**:
  - Run auth/proxy flows and inspect logs to ensure no token/session secret material is emitted.

##### Medium: Insecure TLS mode available via `sslTrustAll`
- **Location**: `src/main/kotlin/com/knowledgespike/feature/kbff/domain/model/KbffConfiguration.kt` (`sslTrustAll`), `src/main/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcService.kt` (`DefaultResourceRetriever(..., true)`)
- **Risk / exploit scenario**: If enabled outside local development, certificate validation is relaxed for ID token validator key retrieval path, increasing MITM risk and trust of attacker-controlled TLS endpoints.
- **Recommended fix**:
  - Enforce `sslTrustAll=false` in production profiles.
  - Fail startup when `sslTrustAll=true` in non-dev environments.
  - Document this flag as local-dev only with explicit warnings.
- **Verification approach**:
  - Config validation test ensuring production config rejects `sslTrustAll=true`.

#### Positive controls observed

- OIDC flow includes state/nonce generation and PKCE challenge (`OidcService`, auth routes).
- ID token validation is performed with Nimbus `IDTokenValidator` and nonce input.
- Proxy target is derived from configured endpoints and checked via trusted-host validation (`UriUtils.isTrustedInternalTarget`).
- Proxy upstream failures return controlled `502` responses without stack trace leakage to clients.
- Security headers are set centrally (`HSTS`, `CSP`, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`).
- Session logout clears server-side session object (`call.sessions.clear<KbffSession>()`).

#### Suggested next actions (priority order)

1. Implement strict redirect allowlisting/relative-only redirect policy.
2. Replace header-presence CSRF with session-bound token validation.
3. Remove/reduce security-sensitive logs and add regression tests for non-leakage.
4. Add environment guardrails preventing `sslTrustAll` in production.
