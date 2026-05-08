### The BFF (Backend-for-Frontend) Model in OIDC

The **Backend-for-Frontend (BFF)** pattern is an architectural approach where a dedicated backend server acts as an intermediary between a frontend application (like an Angular SPA) and its downstream services (like Identity Servers and APIs). When combined with **OpenID Connect (OIDC)**, the BFF becomes a critical security boundary.

---

### 1. Core Components and OIDC Flow

In a traditional OIDC setup for a Single Page Application (SPA), the browser handles the entire authentication flow. In a BFF model, the backend takes over these responsibilities:

*   **Authorization Code Flow with PKCE:** The BFF initiates the OIDC handshake. It redirects the user to the Identity Provider (IdP), handles the authorization code exchange for tokens (Access, ID, and Refresh tokens), and performs the **Proof Key for Code Exchange (PKCE)** validation server-side.
*   **Token Storage:** Crucially, tokens are **never** sent to the browser. They are stored securely in the BFF (often in an encrypted server-side session or a secure cache).
*   **Secure Session:** Instead of a JWT (JSON Web Token) in the browser, the BFF issues a **SameSite, HttpOnly, and Secure cookie** to the frontend. This cookie represents the authenticated session between the browser and the BFF.

---

### 2. Role of the BFF

#### Security Token Management
The BFF is the "token owner." It keeps sensitive Access and Refresh tokens off the client's machine, where they would be vulnerable to exfiltration. It manages the token lifecycle, including:
*   **Acquisition:** Fetching tokens from the IdP.
*   **Renewal:** Using Refresh tokens to get new Access tokens without user intervention.
*   **Revocation:** Cleaning up tokens when the user logs out.

#### Session Management
The BFF bridges the gap between the stateless nature of OIDC and the stateful needs of a web application. It maps the browser's session cookie to the corresponding OIDC tokens stored on the server. If the cookie is valid, the user is considered authenticated.

#### Request Proxying
The BFF acts as a **Reverse Proxy** for API calls. When the frontend needs data from a downstream API:
1.  The frontend calls a local endpoint on the BFF (e.g., `/api/data`).
2.  The BFF intercepts this call, validates the session cookie, and retrieves the stored Access Token.
3.  The BFF attaches the token to the `Authorization: Bearer <token>` header.
4.  The BFF forwards the request to the real API server, receives the response, and streams it back to the frontend.

---

### 3. Security Benefits vs. Pure SPA

Using a BFF provides several layers of protection that a pure client-side SPA cannot:

*   **Mitigation of XSS (Cross-Site Scripting):** In a pure SPA, if an attacker injects a script, they can steal the Access Token from `localStorage` or `sessionStorage`. With a BFF, there is no token in the browser to steal. The session cookie is marked `HttpOnly`, meaning JavaScript cannot access it.
*   **Reduced Attack Surface:** The frontend only knows about the BFF. Downstream API URLs, client secrets, and internal infrastructure remain hidden from the public internet.
*   **Simplified Client Logic:** The Angular/React code doesn't need complex OIDC libraries or token-refresh logic. It simply makes standard HTTP calls to its own backend, and the "magic" of authentication happens via cookies.
*   **Strict CORS Policy:** Since the frontend and BFF usually share the same origin, you can enforce very strict Cross-Origin Resource Sharing (CORS) and Content Security Policy (CSP) rules, further hardening the application.

### Summary of Differences

| Feature | Pure Client-Side SPA | BFF Model |
| :--- | :--- | :--- |
| **Token Location** | Browser (Storage/Memory) | Server-Side (BFF) |
| **Session Mechanism** | Bearer Token (JWT) | Secure HTTP-Only Cookie |
| **Vulnerability to XSS** | High (Token theft) | Low (Tokens are unreachable) |
| **Complexity** | High (Client-side OIDC) | Low (Standard HTTP/Cookies) |
| **Proxying** | Direct to APIs | Indirect via BFF |

---

### 4. Implementation Details (Ktor BFF Library `kbff`)

The `kbff` project provides a Kotlin-idiomatic implementation of the BFF pattern for Ktor applications.

#### 4.1. Configuration DSL (`KbffConfiguration.kt`)
The library uses a type-safe DSL for configuration.

- **`OidcConfiguration`**: Defines the connection to the Identity Provider (Authority URL, Client ID, Client Secret, and Scopes).
- **`ProxyConfiguration`**: Defines which local paths (e.g., `/api/*`) should be proxied to which remote URLs.
- **`KbffConfiguration`**: The main configuration class providing the DSL entry points (`oidc { ... }` and `proxy { ... }`).

#### 4.2. Session Management
To maintain the security boundary, the library handles sessions server-side.

- **`KbffSession`**: A serializable data class that stores the session ID and any associated user data (claims, tokens). This object **never leaves the server**.
- **`KbffSessionStorage`**: An interface for persisting sessions, returning Arrow `Either` for functional error handling.
- **`InMemoryKbffSessionStorage`**: A thread-safe, in-memory implementation of `KbffSessionStorage`, ideal for development or single-instance PoCs.

---

### 5. Usage Examples

Below are examples of how to integrate and configure the `kbff` library in a Ktor application.

#### 5.1. Configure Session Storage

In your Ktor `Application.module`, install the `Sessions` plugin using the `kbff` models and storage:

```kotlin
import com.knowledgespike.feature.kbff.data.repository.InMemoryKbffSessionStorage
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import io.ktor.server.sessions.*

fun Application.module() {
    install(Sessions) {
        cookie<KbffSession>("kbff_session", InMemoryKbffSessionStorage()) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = true // Ensure you are using HTTPS
            cookie.extensions["SameSite"] = "Lax"
        }
    }
}
```

#### 5.2. Using the Configuration DSL

The `kbff` configuration uses a DSL to build the configuration object safely.

```kotlin
import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration

fun Application.configureBff() {
    val bffConfig = KbffConfiguration().apply {
        oidc {
            authority = "https://ids.example.com"
            clientId = "kbff-client"
            clientSecret = "secret"
            scopes = listOf("openid", "profile", "email")
        }
        proxy {
            endpoint("/api/data", "https://api.example.com/data")
        }
    }
    
    // Use bffConfig in your routes or plugins
}
```

#### 5.3. Accessing Session Data in Routes

You can retrieve the session data directly from the Ktor `call`:

```kotlin
routing {
    get("/me") {
        val session = call.sessions.get<KbffSession>()
        if (session != null) {
            // Access session.data (e.g., user claims)
            call.respond(session.data)
        } else {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }
}
```

---

### 6. OIDC Service (`OidcService.kt`)

The `OidcService` is a core component of the `kbff` library that encapsulates the logic for OIDC Authorization Code Flow with PKCE (Proof Key for Code Exchange). It handles state generation, URL construction for the Identity Provider (IdP), and token exchange/refresh operations.

#### 6.1. Key Responsibilities
- **PKCE Support:** Generates cryptographically secure `code_verifier` and its corresponding `code_challenge` (S256).
- **State Management:** Generates unique `state` and `nonce` values to prevent CSRF and replay attacks.
- **Authorization URL:** Constructs the authorization URL for the Identity Provider (IdP) with all required parameters.
- **Token Operations:** Handles server-to-server calls to exchange an authorization code for tokens and to refresh expired access tokens.

#### 6.2. Usage Examples

##### Generating Authorization Parameters and URL
When initiating a login, use `OidcService` to prepare the necessary PKCE and OIDC parameters.

```kotlin
import com.knowledgespike.feature.kbff.domain.service.OidcService
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import org.koin.ktor.ext.inject
import java.util.UUID

val oidcService by inject<OidcService>()

val state = oidcService.generateState()
val nonce = oidcService.generateNonce()
val codeVerifier = oidcService.generateCodeVerifier()
val codeChallenge = oidcService.generateCodeChallenge(codeVerifier)

// Store state and codeVerifier in the session before redirecting
val initialSession = KbffSession(
    sessionId = UUID.randomUUID().toString(),
    state = state,
    nonce = nonce,
    codeVerifier = codeVerifier
)
call.sessions.set(initialSession)

val authUrl = oidcService.getAuthorizationUrl(state, nonce, codeChallenge)
call.respondRedirect(authUrl)
```

##### Exchanging Code for Tokens
In your callback handler, use the `code` from the IdP and the `code_verifier` from the session to fetch tokens.

```kotlin
val code = call.parameters["code"]
val session = call.sessions.get<KbffSession>()
val codeVerifier = session?.codeVerifier

if (code != null && codeVerifier != null) {
    val updatedSession = oidcService.exchangeCodeForTokens(code, codeVerifier)
    if (updatedSession != null) {
        call.sessions.set(updatedSession)
        call.respondRedirect("/")
    }
}
```

##### Refreshing Tokens
When an access token is expired, use the stored refresh token to obtain a new session with updated tokens.

```kotlin
val session = call.sessions.get<KbffSession>()
val refreshToken = session?.refreshToken

// Check if expired (expiresAt is a Long timestamp)
val isExpired = session?.expiresAt?.let { it < System.currentTimeMillis() } ?: false

if (refreshToken != null && isExpired) {
    val refreshedSession = oidcService.refreshTokens(refreshToken)
    if (refreshedSession != null) {
        call.sessions.set(refreshedSession)
    }
}
```

---

### 7. Management Endpoints (`KbffAuthRoutes.kt`)

The `kbff` library provides a set of standard management endpoints to handle common BFF tasks like login, logout, and retrieving user information. These are integrated into your Ktor application using the `kbffAuthRoutes()` extension.

#### 7.1. Login Endpoint (`/bff/login`)
Initiates the OIDC Authorization Code Flow.
- **Support for Return URLs**: Accepts an optional `returnUrl` query parameter. This URL is stored in the session and used to redirect the user back to their original location after a successful login.
- **Example**: `GET /bff/login?returnUrl=/dashboard`

#### 7.2. Callback Endpoint (`/bff/callback`)
The endpoint where the IdP redirects the user after authentication.
- **Token Exchange**: Handles the server-to-server exchange of the authorization code for tokens.
- **PKCE Validation**: Verifies the `state` and `code_verifier` stored in the session.
- **Redirect**: After successful token exchange, redirects the user to the stored `returnUrl` or defaults to `/`.

#### 7.3. User Endpoint (`/bff/user`)
Returns information about the currently authenticated user.
- **Authentication Check**: Returns `401 Unauthorized` if no valid session/access token exists.
- **User Profile**: Returns a JSON object containing the user's `name` (extracted from ID token claims) and the full set of claims.
- **Example Response**:
  ```json
  {
    "name": "John Doe",
    "claims": {
      "sub": "12345",
      "name": "John Doe",
      "preferred_username": "jdoe",
      "email": "john.doe@example.com"
    }
  }
  ```

#### 7.4. Logout Endpoint (`/bff/logout`)
Clears the local session and optionally redirects to the IdP's logout endpoint.
- **Session Invalidation**: Clears the `kbff_session` cookie and server-side session data.
- **OIDC Logout**: If `postLogoutRedirectUri` is configured in the OIDC settings, the BFF will redirect the user to the IdP's logout URL to terminate the global SSO session.

---

### 8. Reverse Proxy and Token Injection (`KbffProxyRoutes.kt`)

The `kbff` library automatically handles request proxying to downstream APIs, ensuring that sensitive tokens are never exposed to the frontend.

#### 8.1. Automatic Request Forwarding
When you call `kbffProxyRoutes()`, the library sets up routes for each endpoint defined in the `proxy { ... }` configuration.

- **Path Mapping**: A local path like `/api/poc` can be mapped to a remote URL like `https://api.example.com/poc`.
- **Sub-path Handling**: Any path segments following the prefix are automatically appended to the target URL (e.g., `/api/poc/users` -> `https://api.example.com/poc/users`).
- **Query Parameters**: All query parameters from the original request are preserved and forwarded.

#### 8.2. Token Injection
For every proxied request, the BFF:
1.  Retrieves the current user's **Access Token** from the server-side session.
2.  Injects the `Authorization: Bearer <token>` header into the outgoing request to the downstream API.

#### 8.3. Automatic Token Refresh
To ensure a seamless user experience, the proxy logic includes built-in token lifecycle management:
- **Expiration Check**: Before forwarding a request, the BFF checks if the Access Token is expired or near expiration (within a 10-second buffer).
- **Silent Refresh**: If the token is expiring, the BFF uses the stored **Refresh Token** to obtain a new set of tokens from the IdP.
- **Session Update**: The new tokens are automatically saved back into the server-side session, and the new Access Token is used for the proxied request.

#### 8.4. Efficient Response Streaming
The proxy uses Ktor's `respondBytesWriter` to stream the response from the downstream API directly back to the client. This ensures:
- **Low Memory Footprint**: The BFF does not buffer the entire response in memory.
- **High Performance**: Data is forwarded as it is received.

---

### 9. Implementation Details

#### 9.1. `KbffSession` Data Model
The session object includes fields to support OIDC and token management:
- `accessToken`, `idToken`, `refreshToken`: The tokens received from the IdP.
- `expiresAt`: Timestamp when the access token expires.
- `returnUrl`: Stores the destination after authentication.
- `claims`: A map of user claims extracted from the ID token (e.g., `name`, `email`).

#### 9.2. ID Token Parsing in `OidcService`
The `OidcService` automatically parses the Base64-encoded payload of the ID token returned by the IdP. This allows the BFF to populate the `claims` map without needing an external JWT validation library at this stage.

