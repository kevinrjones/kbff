---
sessionId: session-260510-114334-16dg
isActive: false
---

# Requirements

### Overview & Goals
Create `docs/SECURITY-PLAN.md` as an actionable implementation playbook derived from `docs/REVIEW.md`, so the team can execute security fixes in a clear, prioritized order.

### Scope
#### In Scope
- Translate each reviewed finding into a step-by-step remediation plan with concrete code touchpoints.
- Cover all four findings from `docs/REVIEW.md`:
  - Open redirect hardening (`KbffAuthRoutes.kt`, `KbffRouteHelpers.kt`, `KbffAuthRoutesTest.kt`)
  - Session-bound CSRF validation (`KbffSecurity.kt`, `KbffSession.kt`, `KbffAuthRoutes.kt`, `KbffProxyRoutes.kt`, related tests)
  - Sensitive logging reduction (`KbffAuthRoutes.kt`, `KbffSecurity.kt`, `OidcService.kt`)
  - `sslTrustAll` production guardrails (`KbffConfiguration.kt`, `OidcService.kt`)
- Include implementation order, dependencies, acceptance criteria, and verification expectations per fix.
- Use checkbox task tracking (`[ ]` / `[x]`) so progress can be tracked directly in the document.

#### Out of Scope
- Implementing code changes themselves.
- Changing runtime configuration or deployment environments.
- Replacing the existing review; this plan complements `docs/REVIEW.md`.

### Functional Requirements
- The plan must be severity-prioritized (High findings first).
- Each finding section must include:
  - objective
  - affected files
  - implementation steps
  - test updates
  - done criteria
- The document must align with existing Ktor/Kotlin architecture boundaries already used in the repo (`domain/model`, `domain/service`, `presentation/auth`, `presentation/route`, `src/test/...`).
- The plan must be executable by another engineer without requiring additional discovery.

# Technical Design

### Current Implementation Context
- Security findings are documented in `docs/REVIEW.md` with explicit locations and recommended fixes.
- Redirect behavior is currently normalized in `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffRouteHelpers.kt` via `normalizeReturnUrl(...)` and used in `KbffAuthRoutes.kt` login/callback flows.
- CSRF validation currently checks header presence only in `src/main/kotlin/com/knowledgespike/feature/kbff/presentation/auth/KbffSecurity.kt` (`verifyCsrfToken(...)`) and is enforced in both `KbffAuthRoutes.kt` and `KbffProxyRoutes.kt`.
- Session model is defined in `src/main/kotlin/com/knowledgespike/feature/kbff/domain/model/KbffSession.kt`.
- Sensitive security logging appears in:
  - `KbffAuthRoutes.kt` (`Setting initial session`)
  - `KbffSecurity.kt` (`Getting session`)
  - `OidcService.kt` (`logRetrievedTokens` at `INFO`)
- TLS trust override is configurable in `KbffConfiguration.kt` (`sslTrustAll`) and consumed in `OidcService.kt` (`DefaultResourceRetriever(..., true)`).
- Existing tests to reference/extend:
  - `src/test/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffAuthRoutesTest.kt`
  - `src/test/kotlin/com/knowledgespike/feature/kbff/presentation/route/KbffProxyRoutesTest.kt`
  - `src/test/kotlin/com/knowledgespike/feature/kbff/domain/service/OidcServiceTest.kt`

### Key Decisions
- `docs/SECURITY-PLAN.md` will be structured as phased implementation workstreams, not just narrative prose.
- Each workstream will map directly to one finding from `docs/REVIEW.md` and include concrete file-level change guidance.
- The document will use task checklists with parent/subtask semantics so it can serve as a living execution tracker.
- Verification steps will be attached to each workstream (unit/integration/manual checks) instead of as a single global test section.

### Proposed Changes
- Add `docs/SECURITY-PLAN.md` with this structure:
  1. **Purpose & Inputs** (link to `docs/REVIEW.md`, assumptions, priority model)
  2. **Execution Order / Milestones** (High before Medium)
  3. **Workstream A: Redirect Hardening**
     - relative URL / allowlist strategy
     - helper and route changes
     - route test scenarios
  4. **Workstream B: Session-Bound CSRF**
     - session token model updates
     - validation/rotation/clear semantics
     - logout + proxy negative/positive tests
  5. **Workstream C: Logging Sanitization**
     - remove session object logging
     - token log-level and redaction policy
     - regression checks for log leakage
  6. **Workstream D: TLS Guardrails**
     - environment constraints for `sslTrustAll`
     - startup validation expectations
     - config validation tests
  7. **Release Gate Checklist**
     - pre-merge checks, security regression checks, documentation updates
- Include explicit “Definition of Done” checkboxes for each workstream.
- (Optional) Add a brief cross-link in `docs/REVIEW.md` to the new implementation plan if helpful for navigation.

### File Structure
- **Add:** `docs/SECURITY-PLAN.md`
- **Optional small edit:** `docs/REVIEW.md` (link to `SECURITY-PLAN.md`)

# Delivery Steps

### ✓ Step 1: Build the remediation blueprint and document skeleton
`docs/SECURITY-PLAN.md` contains a complete scaffold aligned to the current review findings and codebase.

- Extract all actionable items from `docs/REVIEW.md` and map each to one remediation workstream.
- Define milestone ordering (High findings first, then Medium) and capture dependencies between workstreams.
- Create the document skeleton with checklist-driven sections for objective, affected files, implementation steps, tests, and done criteria.

### ✓ Step 2: Author detailed high-risk implementation workstreams
The plan provides step-by-step implementation guidance for redirect and CSRF fixes with concrete test expectations.

- Write the redirect-hardening workstream referencing `KbffAuthRoutes.kt`, `KbffRouteHelpers.kt`, and `KbffAuthRoutesTest.kt`.
- Write the session-bound CSRF workstream referencing `KbffSecurity.kt`, `KbffSession.kt`, `KbffAuthRoutes.kt`, `KbffProxyRoutes.kt`, and associated tests.
- For both workstreams, include explicit negative/positive verification scenarios and completion checklists.

### ✓ Step 3: Author medium-risk workstreams and finalize execution gates
The plan includes logging and TLS guardrail steps plus a practical release-readiness checklist.

- Write the logging-sanitization workstream for `KbffAuthRoutes.kt`, `KbffSecurity.kt`, and `OidcService.kt` with non-leakage validation steps.
- Write the `sslTrustAll` guardrail workstream for `KbffConfiguration.kt` and `OidcService.kt`, including production-safety validation expectations.
- Add final release gates and traceability checks confirming every finding in `docs/REVIEW.md` is covered in `docs/SECURITY-PLAN.md`.