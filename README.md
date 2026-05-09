# KBFF - Ktor Backend-for-Frontend Library

[![Build and Test](https://github.com/kevinrjones/kbff/actions/workflows/build.yml/badge.svg)](https://github.com/kevinrjones/kbff/actions/workflows/build.yml)
![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-blue.svg?logo=kotlin)
![Ktor](https://img.shields.io/badge/ktor-3.4.1-blue.svg?logo=ktor)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)

KBFF is a Ktor library designed to simplify the implementation of the **Backend-for-Frontend (BFF)** pattern using OpenID Connect (OIDC). It manages the complexities of authentication, secure session storage, and request proxying, allowing you to build secure modern web applications.

## Key Features

- **OIDC Integration**: Support for Authorization Code Flow with PKCE (Proof Key for Code Exchange).
- **Secure Session Management**: Server-side sessions using secure, `HttpOnly`, `SameSite` cookies to mitigate XSS and CSRF.
- **Reverse Proxy**: Built-in reverse proxy that automatically injects OIDC Access Tokens into requests forwarded to downstream APIs.
- **Token Lifecycle**: Automatic handling of token expiration and silent refresh using Refresh Tokens.
- **Security Hardening**: Standard security headers (CSP, HSTS, X-Frame-Options, etc.) and CSRF protection for state-changing requests.
- **BFF Endpoints**: Pre-configured routes for `login`, `callback`, `logout`, and retrieving authenticated `user` information.

## Prerequisites

- **Java 21** or higher.
- **Ktor 3.x** or higher.
- A compatible OpenID Connect (OIDC) Identity Provider (e.g., Keycloak, Auth0, Entra ID).

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.knowledgespike:kbff:0.1.0")
}
```

## Configuration

Configure the library using the `KbffConfiguration` DSL:

```kotlin
val kbffConfig = KbffConfiguration().apply {
    oidc {
        authority = "https://your-idp.com/realms/your-realm"
        clientId = "your-client-id"
        clientSecret = "your-client-secret"
        scopes = listOf("openid", "profile", "email", "offline_access")
        redirectUri = "http://localhost:8080/signin-oidc"
        postLogoutRedirectUri = "http://localhost:8080/"
    }
    proxy {
        // Map local path to downstream service
        endpoint("/api", "https://downstream-service.com/api/v1")
    }
    security {
        enableCsrf = true
        csp = "default-src 'self';"
    }
}
```

## Usage

Integrating KBFF into your Ktor application:

```kotlin
fun Application.module() {
    // 1. Dependency Injection (Example using Koin)
    install(Koin) {
        modules(module {
            single { kbffConfig }
            single { HttpClient(CIO) { install(ContentNegotiation) { json() } } }
            single { OidcService(get(), get()) }
        })
    }

    val oidcService by inject<OidcService>()
    val httpClient by inject<HttpClient>()

    // 2. Setup Sessions (Required)
    install(Sessions) {
        cookie<KbffSession>("BFF_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = true // Recommended for production
            cookie.extensions["SameSite"] = "Lax"
        }
    }

    // 3. Apply Security Headers
    installKbffSecurityHeaders(kbffConfig)

    // 4. Configure Authentication Provider
    install(Authentication) {
        kbffOidc("kbff-auth")
    }

    // 5. Register BFF and Proxy Routes
    routing {
        kbffAuthRoutes(oidcService, kbffConfig)
        kbffProxyRoutes(kbffConfig, httpClient, oidcService)
        
        // Protect local routes with the BFF authentication
        authenticate("kbff-auth") {
            get("/protected") {
                call.respondText("This is a protected local route")
            }
        }
    }
}
```

## Built-in Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/bff/login` | `GET` | Initiates the OIDC login flow. Accepts `returnUrl` parameter. |
| `/signin-oidc` | `GET` | OIDC callback handler (configured via `redirectUri`). |
| `/bff/logout` | `POST` | Logs out the user locally and from the IDP. Requires CSRF header. |
| `/bff/user` | `GET` | Returns the current user's claims as JSON. |

## Security

### CSRF Protection
When enabled, all `POST`, `PUT`, `DELETE`, and `PATCH` requests must include a CSRF header. By default, this header is `X-CSRF`.

### Security Headers
The `installKbffSecurityHeaders(config)` helper sets up:
- **HSTS** (if enabled in config)
- **CSP**: Content-Security-Policy
- **X-Frame-Options**: `DENY` (configurable)
- **X-Content-Type-Options**: `nosniff`
- **Referrer-Policy**: `strict-origin-when-cross-origin`

## Error Handling
The library returns standardized JSON responses for errors:
```json
{
  "error": "unauthorized",
  "error_description": "User is not authenticated"
}
```

Common error codes:
- `unauthorized`: User is not authenticated.
- `forbidden`: CSRF check failed.
- `invalid_request`: Missing or invalid parameters.
- `authentication_failed`: OIDC token exchange failed.
