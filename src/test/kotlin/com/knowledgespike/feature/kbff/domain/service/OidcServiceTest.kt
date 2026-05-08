package com.knowledgespike.feature.kbff.domain.service

import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.MetadataUnavailableError
import arrow.core.Either
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.*
import strikt.assertions.hasLength

class OidcServiceTest {

    private val metadataResponse = """
        {
          "issuer": "https://auth.example.com",
          "authorization_endpoint": "https://auth.example.com/connect/authorize",
          "token_endpoint": "https://auth.example.com/connect/token",
          "userinfo_endpoint": "https://auth.example.com/connect/userinfo",
          "jwks_uri": "https://auth.example.com/.well-known/jwks.json",
          "end_session_endpoint": "https://auth.example.com/connect/logout",
          "response_types_supported": ["code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"]
        }
    """.trimIndent()

    private val metadataWithParResponse = """
        {
          "issuer": "https://auth.example.com",
          "authorization_endpoint": "https://auth.example.com/connect/authorize",
          "token_endpoint": "https://auth.example.com/connect/token",
          "userinfo_endpoint": "https://auth.example.com/connect/userinfo",
          "jwks_uri": "https://auth.example.com/.well-known/jwks.json",
          "end_session_endpoint": "https://auth.example.com/connect/logout",
          "pushed_authorization_request_endpoint": "https://auth.example.com/connect/par",
          "response_types_supported": ["code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"]
        }
    """.trimIndent()

    private fun createService(mockEngine: MockEngine): OidcService {
        val client = HttpClient(mockEngine)
        val config = KbffConfiguration().apply {
            oidc {
                authority = "https://auth.example.com"
                clientId = "test-client"
                clientSecret = "test-secret"
                redirectUri = "https://app.example.com/callback"
                postLogoutRedirectUri = "https://app.example.com/logout-callback"
            }
        }
        return OidcService(client, config)
    }

    @Test
    fun `test random string generation`() {
        val service = createService(MockEngine { respondOk() })
        
        expectThat(service.generateState()).hasLength(43) // Base64 encoded 32 bytes
        expectThat(service.generateNonce()).hasLength(43)
        expectThat(service.generateCodeVerifier().length).isEqualTo(43)
    }

    @Test
    fun `test code challenge generation`() {
        val service = createService(MockEngine { respondOk() })
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = service.generateCodeChallenge(verifier)

        expectThat(challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `test authorization url construction`(): Unit = runBlocking {
        val service = createService(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/openid-configuration") {
                respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        })
        val result = service.getAuthorizationUrl("state123", "nonce456", "challenge789")
        val url = when (result) {
            is Either.Left -> error("Expected authorization URL, got ${result.value.message}")
            is Either.Right -> result.value
        }
        
        val uri = Url(url)
        expectThat(uri.protocol).isEqualTo(URLProtocol.HTTPS)
        expectThat(uri.host).isEqualTo("auth.example.com")
        expectThat(uri.encodedPath).isEqualTo("/connect/authorize")
        expectThat(uri.parameters["state"]).isEqualTo("state123")
        expectThat(uri.parameters["nonce"]).isEqualTo("nonce456")
        expectThat(uri.parameters["code_challenge"]).isEqualTo("challenge789")
        expectThat(uri.parameters["client_id"]).isEqualTo("test-client")
    }

    @Test
    fun `test logout url construction`(): Unit = runBlocking {
        val service = createService(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/openid-configuration") {
                respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        })
        val result = service.getLogoutUrl("id-token-123")
        val url = when (result) {
            is Either.Left -> error("Expected logout URL, got ${result.value.message}")
            is Either.Right -> result.value
        }
        
        val uri = Url(url!!)
        expectThat(uri.encodedPath).isEqualTo("/connect/logout")
        expectThat(uri.parameters["id_token_hint"]).isEqualTo("id-token-123")
        expectThat(uri.parameters["post_logout_redirect_uri"]).isEqualTo("https://app.example.com/logout-callback")
    }

    @Test
    fun `test authorization url fails when oidc discovery returns non json body`(): Unit = runBlocking {
        val service = createService(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/openid-configuration") {
                respond("<html>not json</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        })

        val result = service.getAuthorizationUrl("state123", "nonce456", "challenge789")
        val error = when (result) {
            is Either.Left -> result.value
            is Either.Right -> error("Expected an OIDC metadata error")
        }

        expectThat(error).isA<MetadataUnavailableError>()
        expectThat(error.message).contains("Failed to parse OIDC metadata")
    }

    @Test
    fun `test exchange code for tokens`(): Unit = runBlocking {
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/connect/token" -> {
                    expectThat(request.method).isEqualTo(HttpMethod.Post)
                    val responseHeaders = headersOf("Content-Type", "application/json")
                    val content = buildJsonObject {
                        put("access_token", "access-123")
                        put("token_type", "Bearer")
                        put("refresh_token", "refresh-123")
                        put("expires_in", 3600)
                    }.toString()
                    respond(content, HttpStatusCode.OK, responseHeaders)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        
        val service = createService(mockEngine)
        val sessionResult = service.exchangeCodeForTokens("code123", service.generateCodeVerifier())
        val session = when (sessionResult) {
            is Either.Left -> error("Expected session, got ${sessionResult.value.message}")
            is Either.Right -> sessionResult.value
        }

        expectThat(session).and {
            get { accessToken }.isEqualTo("access-123")
            get { refreshToken }.isEqualTo("refresh-123")
        }
    }

    @Test
    fun `test refresh tokens`(): Unit = runBlocking {
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/connect/token" -> {
                    val responseHeaders = headersOf("Content-Type", "application/json")
                    val content = buildJsonObject {
                        put("access_token", "new-access-123")
                        put("token_type", "Bearer")
                        put("refresh_token", "new-refresh-123")
                        put("expires_in", 3600)
                    }.toString()

                    respond(content, HttpStatusCode.OK, responseHeaders)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        
        val service = createService(mockEngine)
        val sessionResult = service.refreshTokens("old-refresh-token")
        val session = when (sessionResult) {
            is Either.Left -> error("Expected refreshed session, got ${sessionResult.value.message}")
            is Either.Right -> sessionResult.value
        }

        expectThat(session).and {
            get { accessToken }.isEqualTo("new-access-123")
            get { refreshToken }.isEqualTo("new-refresh-123")
        }
    }

    @Test
    fun `test authorization url uses request_uri when PAR is supported`(): Unit = runBlocking {
        val service = createService(MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    metadataWithParResponse,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                "/connect/par" -> {
                    val responseHeaders = headersOf("Content-Type", "application/json")
                    val content = buildJsonObject {
                        put("request_uri", "urn:ietf:params:oauth:request_uri:test123")
                        put("expires_in", 90)
                    }.toString()
                    respond(content, HttpStatusCode.Created, responseHeaders)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        })

        val result = service.getAuthorizationUrl("state123", "nonce456", "challenge789")
        val url = when (result) {
            is Either.Left -> error("Expected PAR authorization URL, got ${result.value.message}")
            is Either.Right -> result.value
        }
        val uri = Url(url)

        expectThat(uri.parameters["request_uri"]).isEqualTo("urn:ietf:params:oauth:request_uri:test123")
        expectThat(uri.parameters["client_id"]).isEqualTo("test-client")
    }

    @Test
    fun `test token exchange does not use client secret when not configured`(): Unit = runBlocking {
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/connect/token" -> {
                    val authorizationHeader = request.headers[HttpHeaders.Authorization]
                    expectThat(authorizationHeader == null || authorizationHeader.isBlank()).isEqualTo(true)
                    val responseHeaders = headersOf("Content-Type", "application/json")
                    val content = buildJsonObject {
                        put("access_token", "access-123")
                        put("token_type", "Bearer")
                        put("refresh_token", "refresh-123")
                        put("expires_in", 3600)
                    }.toString()
                    respond(content, HttpStatusCode.OK, responseHeaders)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(mockEngine)
        val config = KbffConfiguration().apply {
            oidc {
                authority = "https://auth.example.com"
                clientId = "test-client"
                clientSecret = ""
                redirectUri = "https://app.example.com/callback"
                postLogoutRedirectUri = "https://app.example.com/logout-callback"
            }
        }

        val service = OidcService(client, config)
        val sessionResult = service.exchangeCodeForTokens("code123", service.generateCodeVerifier())
        when (sessionResult) {
            is Either.Left -> error("Expected session, got ${sessionResult.value.message}")
            is Either.Right -> expectThat(sessionResult.value.accessToken).isEqualTo("access-123")
        }
    }

    @Test
    fun `test token exchange retries without secret when invalid client is returned`(): Unit = runBlocking {
        var tokenRequestCount = 0
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/connect/token" -> {
                    tokenRequestCount += 1
                    if (tokenRequestCount == 1) {
                        expectThat(request.headers[HttpHeaders.Authorization]).isNotNull()
                        respond(
                            "{\"error\":\"invalid_client\"}",
                            HttpStatusCode.Unauthorized,
                            headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        expectThat(request.headers[HttpHeaders.Authorization] ?: "").isEqualTo("")
                        respond(
                            buildJsonObject {
                                put("access_token", "access-123")
                                put("token_type", "Bearer")
                                put("refresh_token", "refresh-123")
                                put("expires_in", 3600)
                            }.toString(),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }

                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val service = createService(mockEngine)
        val sessionResult = service.exchangeCodeForTokens("code123", service.generateCodeVerifier())

        expectThat(tokenRequestCount).isEqualTo(2)
        when (sessionResult) {
            is Either.Left -> error("Expected session after retry, got ${sessionResult.value.message}")
            is Either.Right -> expectThat(sessionResult.value.accessToken).isEqualTo("access-123")
        }
    }
    @Test
    fun `test getUserInfoClaims returns claims`(): Unit = runBlocking {
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> {
                    respond(metadataResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/connect/userinfo" -> {
                    val authHeader = request.headers[HttpHeaders.Authorization]
                    if (authHeader == "Bearer access-token") {
                        val response = buildJsonObject {
                            put("sub", "user123")
                            put("given_name", "John")
                            put("family_name", "Doe")
                            put("roles", kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive("admin"))
                                add(kotlinx.serialization.json.JsonPrimitive("user"))
                            })
                        }.toString()
                        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val service = createService(mockEngine)
        val result = service.getUserInfoClaims("access-token")
        
        val claims = when (result) {
            is Either.Left -> error("Expected claims, got ${result.value.message}")
            is Either.Right -> result.value
        }

        expectThat(claims).hasSize(5)
        expectThat(claims.find { it.type == "given_name" }?.value).isEqualTo("John")
        expectThat(claims.find { it.type == "family_name" }?.value).isEqualTo("Doe")
        expectThat(claims.filter { it.type == "roles" }).hasSize(2)
    }
}
