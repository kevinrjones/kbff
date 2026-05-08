### kbff (Kotlin Backend-for-Frontend) Library Documentation

The `kbff` library provides Ktor-based infrastructure for building a Backend-for-Frontend (BFF) with OpenID Connect (OIDC) authentication, secure request proxying, and comprehensive security headers.

---

### Key Features

- **OIDC Authentication**: Built-in routes for login, callback, and logout using standard OIDC flows (including PKCE).
- **Secure Proxying**: Automatically proxy requests to downstream APIs while injecting Bearer tokens and managing token refresh.
- **Security Headers**: Easy configuration of HSTS, CSP, X-Frame-Options, and more.
- **CSRF Protection**: Optional, configurable CSRF header verification for state-changing requests (POST, PUT, DELETE, etc.).
- **User Information**: Endpoint to expose authenticated user claims to the frontend.

---

### Configuration

The library uses a DSL-based configuration model via the `KbffConfiguration` class.

#### OIDC Configuration

```kotlin
val config = KbffConfiguration().apply {
    oidc {
        authority = "https://identity-server"
        discoveryUrl = "https://identity-server/.well-known/openid-configuration"
        clientId = "your-client-id"
        clientSecret = "your-client-secret"
        scopes = listOf("openid", "profile", "api-scope")
        redirectUri = "https://your-app/signin-oidc"
        postLogoutRedirectUri = "https://your-app/"
    }
}
```

- `authority`: The OIDC provider's authority URL.
- `discoveryUrl`: The URL to the OIDC discovery document.
- `clientId` & `clientSecret`: Your application credentials.
- `scopes`: The list of scopes to request.
- `redirectUri`: The URI where the provider will redirect after authentication.
- `postLogoutRedirectUri`: The URI to redirect to after logout.

#### Security Configuration

```kotlin
    security {
        enableCsrf = true
        csrfHeaderName = "X-BFF-CSRF"
        hsts = true
        hstsIncludeSubdomains = true
        csp = "default-src 'self'; ..."
        frameOptions = "DENY"
        contentTypeOptions = "nosniff"
        permissionsPolicy = "geolocation=()"
    }
```

- `enableCsrf`: If true, non-GET requests will require a CSRF header matching the session's token.
- `csrfHeaderName`: The name of the header expected (default: `X-BFF-CSRF`).
- `hsts`: Enable HTTP Strict Transport Security (HSTS).
- `csp`: Define your Content-Security-Policy.
- `frameOptions`: Set the `X-Frame-Options` header.
- `contentTypeOptions`: Set the `X-Content-Type-Options` header.

#### Proxy Configuration

```kotlin
    proxy {
        endpoint(path = "/api/v1", targetUrl = "https://downstream-service/v1")
        endpoint(path = "/api/v2", targetUrl = "https://another-service/v2")
    }
```

- Each `endpoint` maps a local path prefix to a downstream target URL. Requests to these paths will include the authenticated user's access token in the `Authorization: Bearer` header.

---

### Installation and Setup

1. **Install Security Headers**:
   Apply security headers to your Ktor application.
   ```kotlin
   application.installKbffSecurityHeaders(kbffConfiguration)
   ```

2. **Configure Authentication**:
   Define the authentication provider in your security setup.
   ```kotlin
   install(Authentication) {
       kbffOidc(kbffConfiguration)
   }
   ```

3. **Register Routes**:
   Add the authentication and proxy routes to your routing configuration.
   ```kotlin
   routing {
       kbffAuthRoutes(
           oidcService = oidcService,
           configuration = kbffConfiguration,
           port = 8080,
           host = "localhost"
       )
       
       kbffProxyRoutes(
           configuration = kbffConfiguration,
           httpClient = httpClient,
           oidcService = oidcService
       )
   }
   ```

---

### Built-in Endpoints

- `GET /bff/login?returnUrl=...`: Initiates the OIDC login flow.
- `GET /signin-oidc`: The callback endpoint for the OIDC provider (as configured in `redirectUri`).
- `POST /bff/logout`: Logs the user out locally and (if configured) from the OIDC provider. Requires a valid CSRF header.
- `GET /bff/user`: Returns the current user's claims in JSON format.

---

### Token Management

The library automatically manages access tokens for proxied requests:
- If an access token is expired or close to expiration (within 10 seconds), and a refresh token is available, it will automatically attempt a token refresh.
- If the refresh succeeds, the new tokens are stored in the session and used for the current and future requests.
- If refresh fails, the request is rejected (401), and the user may need to re-authenticate.

### CSRF Protection

When `enableCsrf` is active, all state-changing requests (POST, PUT, DELETE, PATCH) must include a header matching the session's `csrfToken`. The token can be retrieved from the `/bff/user` endpoint or directly from the session if accessible.

```http
POST /api/v1/update-data
X-BFF-CSRF: <your-session-csrf-token>
Content-Type: application/json

{ ... }
```
