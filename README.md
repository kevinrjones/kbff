# KBFF - Ktor Backend-for-Frontend Library

KBFF is a Ktor library that simplifies the implementation of the Backend-for-Frontend (BFF) pattern using OpenID Connect (OIDC). It handles authentication, secure session management, and provides a reverse proxy to downstream APIs with automatic token injection and refresh.

## Features

- **OIDC Integration**: Support for Authorization Code Flow with PKCE.
- **Secure Session Management**: Server-side sessions with secure cookie attributes (`HttpOnly`, `SameSite`, `Secure`).
- **Reverse Proxy**: Transparently forward requests to downstream services.
- **Token Injection**: Automatically injects OIDC Access Tokens into proxied requests.
- **Token Refresh**: Automatically refreshes expired Access Tokens using Refresh Tokens.
- **Security Hardening**: Includes recommended security headers (CSP, HSTS, etc.) and CSRF protection.
- **BFF Management Endpoints**: Built-in routes for login, logout, and user information.

## Installation

Add the library to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.knowledgespike:kbff:1.0.0")
}
```

## Configuration

You can configure KBFF using a DSL:

```kotlin
val kbffConfig = KbffConfiguration().apply {
    oidc {
        authority = "https://your-idp.com"
        clientId = "your-client-id"
        clientSecret = "your-client-secret"
        scopes = listOf("openid", "profile", "email", "offline_access")
        redirectUri = "http://localhost:8080/bff/callback"
        postLogoutRedirectUri = "http://localhost:8080/"
    }
    proxy {
        endpoint("/api/data", "https://downstream-service.com/api/v1")
    }
    security {
        enableCsrf = true
        csp = "default-src 'self';"
    }
}
```

## Usage

Register the library in your Ktor application:

```kotlin
fun Application.module() {
    // 1. Install Koin and provide KbffConfiguration and HttpClient
    install(Koin) {
        modules(module {
            single { kbffConfig }
            single { HttpClient(Apache) { install(ContentNegotiation) { json() } } }
            single { OidcService(get(), get()) }
        })
    }

    // 2. Install Sessions
    install(Sessions) {
        cookie<KbffSession>("BFF_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = true // Use true in production
            cookie.extensions["SameSite"] = "Lax"
        }
    }

    // 3. Install Security Headers
    installKbffSecurityHeaders()

    // 4. Configure Authentication
    install(Authentication) {
        kbffOidc("kbff-auth")
    }

    // 5. Register Routes
    routing {
        kbffAuthRoutes()
        kbffProxyRoutes()
        
        // Protected local routes
        authenticate("kbff-auth") {
            get("/protected") {
                call.respondText("This is protected")
            }
        }
    }
}
```

## Security Best Practices

### CSRF Protection
KBFF includes CSRF protection for non-GET requests. By default, it expects a custom header `X-BFF-CSRF` to be present on all `POST`, `PUT`, `DELETE`, etc., requests.

### Security Headers
`installKbffSecurityHeaders()` helper sets up:
- **Content Security Policy (CSP)**
- **HTTP Strict Transport Security (HSTS)**
- **X-Frame-Options**: Set to `DENY` by default.
- **X-Content-Type-Options**: Set to `nosniff` by default.
- **Referrer-Policy**: Set to `strict-origin-when-cross-origin`.

## Error Handling
KBFF returns standardized JSON error responses:

```json
{
  "error": "error_code",
  "error_description": "A human-readable message"
}
```

Common error codes:
- `unauthorized`: User is not authenticated.
- `forbidden`: CSRF check failed.
- `invalid_request`: Missing or invalid parameters.
- `authentication_failed`: OIDC token exchange failed.
