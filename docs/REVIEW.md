### Code Review: `kbff` Library

I have completed a comprehensive review of the `kbff` (Kotlin Backend For Frontend) library, focusing on architectural patterns, security best practices, and overall code quality.

#### Summary of Findings
The `kbff` library is a well-structured implementation of the BFF pattern, following modern Kotlin idioms and functional programming principles. It correctly implements secure OIDC flows including **PKCE** and **Pushed Authorization Requests (PAR)**.

#### Key Strengths
- **Functional Style**: Excellent use of Arrow's `Either` for error handling and data flow, ensuring predictable execution paths.
- **Security Features**: Native support for CSRF protection via custom headers, HSTS, CSP, and secure cookie-based session management.
- **Protocol Compliance**: Proper implementation of OIDC flows, including state/nonce verification and ID token validation.
- **Robust Proxy**: The proxy implementation handles token refresh transparently and supports streaming responses.

#### Areas for Improvement & Recommendations

##### 1. Security & Information Disclosure
- **Sensitive Data Logging**: `OidcService.kt` currently logs `access_token`, `id_token`, and `refresh_token` at the `INFO` level.
    - **Risk**: High. Sensitive tokens will appear in application logs (Loki), potentially exposing them to unauthorized personnel or systems.
    - **Fix**: Mask tokens or remove them from logs entirely.
- **Silent Validation Failures**: In `OidcService.parseAndValidateIdToken`, validation failures return an `emptyMap()` instead of propagating an error.
    - **Risk**: Moderate. While the session might still "work", the application proceeds with unverified identity claims.
    - **Fix**: Propagate the validation error via `Either`.

##### 2. Reliability & Resilience
- **Metadata Caching**: `OidcService` uses a simple `metadataCache` with a 5-minute TTL.
    - **Recommendation**: Consider adding a retry mechanism if the initial metadata fetch fails during application startup.
- **Timeout Configuration**: The `HttpClient` used for proxying and OIDC calls should have explicit connect and socket timeouts defined in the configuration.

##### 3. Code Quality & Consistency
- **Builder Consistency**: Most configuration blocks use builders, which is good for DSLs. Ensure all optional security headers (like `Permissions-Policy`) are also exposed through these builders.
- **Standard Port Logic**: `KbffRouteHelpers.buildAuthorityUrl` has hardcoded checks for ports 80 and 443. This is fine but could be centralized in a URI utility.

#### Conclusion
The `kbff` library is in a very good state. Addressing the token logging and improving the error handling for ID token validation should be prioritized to enhance the security posture before production use.

### 2026-03-27

#### 11:04

Recap from the last recap entry to now:

- Updated `bff/docs/PORT-ANGULAR.md` to align with the migration direction: fully move Angular into Ktor-hosted `acs-web`, preserve BFF/NFF + OIDC behavior, and use the existing ASP.NET PoC behavior as parity guidance.
- Created `bff/docs/VITE-PORT.md` with a structured migration plan from the Vite PoC to an Angular PoC, keeping feature parity while adopting the `acs-web` Angular structure.
- Created `bff/docs/VITE-PORT-TASKS.md` and implemented task sections in sequence:
  - Completed tasks `1` and `2` (scope/parity capture and target Angular mapping).
  - Implemented task `3` by scaffolding Angular under `bff/acs-web/ClientApp`, adding routing shell and dev proxy support.
  - Added and completed task `4` to integrate Angular build into Gradle (`./gradlew :acs-web:build`) using Node-plugin-based task wiring and resource sync into Ktor static assets.
  - Completed task `5` by adding Angular core models and API services for `/bff/user` and players search parity, including unauthorized/error/network handling.
  - Completed tasks `6` to `9` by implementing feature pages/routes, BFF login initiation flow, and Ktor SPA fallback boundaries for frontend routes vs backend endpoints.
  - Completed task `10` parity verification with Angular unit tests and API service specs; `10.4` remains intentionally unchecked because full OIDC session-creation verification requires a live IdP environment.
- Fixed runtime static asset delivery issues encountered during app run:
  - Resolved MIME mismatch from SPA fallback incorrectly returning `index.html` for hashed JS/CSS requests.
  - Adjusted Ktor fallback behavior so file-like asset paths are served with file-based MIME type when present or return `404` when missing.
  - Added/updated regression tests in `AcsWebTest.kt` for missing-asset and real JS-asset serving behavior.
- Verification performed during this period included:
  - `npm run build` in `bff/acs-web/ClientApp` (pass).
  - `npm run test -- --watch=false --browsers=ChromeHeadless` (pass, `12/12`).
  - `./gradlew :acs-web:build` and `./gradlew :acs-web:test` from `bff/` (pass), with expected local OIDC metadata warnings where no reachable IdP was configured.

Git commits reviewed for context:

- `c98a702` — docs: add migration guides for porting Vite-based PoC to Angular.
- `4e6c906` — docs: add `PORT-ANGULAR.md` migration document.
- No newer commits were found covering the later task-implementation work above, indicating those changes were completed in the working tree during this session flow rather than captured in additional commits.
