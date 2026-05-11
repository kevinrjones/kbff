package com.knowledgespike.feature.kbff.presentation.route

import arrow.core.right
import com.knowledgespike.feature.kbff.domain.model.KbffClaim
import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.domain.service.OidcService
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class KbffAuthRoutesTest {

    @Test
    fun `normalizeReturnUrl keeps safe relative path`() {
        val result = normalizeReturnUrl(
            returnUrl = "/dashboard?tab=claims#section1",
            callbackPath = "/signin-oidc"
        )

        expectThat(result).isEqualTo("/dashboard?tab=claims")
    }

    @Test
    fun `normalizeReturnUrl rejects absolute external url`() {
        val result = normalizeReturnUrl(
            returnUrl = "https://evil.example/phish",
            callbackPath = "/signin-oidc"
        )

        expectThat(result).isEqualTo("/")
    }

    @Test
    fun `normalizeReturnUrl rejects callback path`() {
        val result = normalizeReturnUrl(
            returnUrl = "/signin-oidc",
            callbackPath = "/signin-oidc"
        )

        expectThat(result).isEqualTo("/")
    }

    @Test
    fun `normalizeReturnUrl rejects protocol relative url`() {
        val result = normalizeReturnUrl(
            returnUrl = "//evil.example/path",
            callbackPath = "/signin-oidc"
        )

        expectThat(result).isEqualTo("/")
    }

    @Test
    fun `test login redirect`() = testApplication {
        val oidcService = mockk<OidcService>()
        every { oidcService.generateState() } returns "state123"
        every { oidcService.generateNonce() } returns "nonce456"
        every { oidcService.generateCodeVerifier() } returns "verifier789"
        every { oidcService.generateCodeChallenge(any()) } returns "challenge012"
        coEvery { oidcService.getAuthorizationUrl(any(), any(), any()) } returns "https://auth.com/login".right()

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration()
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = createClient { followRedirects = false }.get("/bff/login?returnUrl=/dashboard")
        
        expectThat(response.status).isEqualTo(HttpStatusCode.Found)
        expectThat(response.headers[HttpHeaders.Location]).isEqualTo("https://auth.com/login")
        
        // Verify session was set
        val sessionCookie = response.headers[HttpHeaders.SetCookie]
        expectThat(sessionCookie).isNotNull().contains("KBFF_SESSION")
    }

    @Test
    fun `test callback success`() = testApplication {
        val oidcService = mockk<OidcService>()
        every { oidcService.generateState() } returns "new-csrf-token"
        val initialSession = KbffSession(
            sessionId = "sid123",
            state = "state123",
            codeVerifier = "verifier789",
            returnUrl = "/dashboard"
        )
        val updatedSession = initialSession.copy(accessToken = "access-token", idToken = "id-token")

        coEvery { oidcService.exchangeCodeForTokens("code123", "verifier789", any()) } returns updatedSession.right()
        coEvery { oidcService.getUserInfoClaims("access-token") } returns listOf<KbffClaim>(KbffClaim("given_name", "John")).right()

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration()
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                    serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = initialSession
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = createClient { followRedirects = false }.get("/signin-oidc?code=code123&state=state123") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Found)
        expectThat(response.headers[HttpHeaders.Location]).isEqualTo("/dashboard")
    }

    @Test
    fun `test callback success should not redirect back to callback path`() = testApplication {
        val oidcService = mockk<OidcService>()
        every { oidcService.generateState() } returns "new-csrf-token"
        val initialSession = KbffSession(
            sessionId = "sid123",
            state = "state123",
            codeVerifier = "verifier789",
            returnUrl = "/signin-oidc"
        )
        val updatedSession = initialSession.copy(accessToken = "access-token", idToken = "id-token")

        coEvery { oidcService.exchangeCodeForTokens("code123", "verifier789", any()) } returns updatedSession.right()
        coEvery { oidcService.getUserInfoClaims("access-token") } returns emptyList<KbffClaim>().right()

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration()
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                    serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = initialSession
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = createClient { followRedirects = false }.get("/signin-oidc?code=code123&state=state123") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Found)
        expectThat(response.headers[HttpHeaders.Location]).isEqualTo("/")
    }

    @Test
    fun `test callback success should redirect to fallback for unsafe external return url`() = testApplication {
        val oidcService = mockk<OidcService>()
        every { oidcService.generateState() } returns "new-csrf-token"
        val initialSession = KbffSession(
            sessionId = "sid123",
            state = "state123",
            codeVerifier = "verifier789",
            returnUrl = "https://evil.example/phish"
        )
        val updatedSession = initialSession.copy(accessToken = "access-token", idToken = "id-token")

        coEvery { oidcService.exchangeCodeForTokens("code123", "verifier789", any()) } returns updatedSession.right()
        coEvery { oidcService.getUserInfoClaims("access-token") } returns emptyList<KbffClaim>().right()

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration()
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                    serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = initialSession
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = createClient { followRedirects = false }.get("/signin-oidc?code=code123&state=state123") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Found)
        expectThat(response.headers[HttpHeaders.Location]).isEqualTo("/")
    }

    @Test
    fun `test user endpoint`() = testApplication {
        val oidcService = mockk<OidcService>()
        val session = KbffSession(
            sessionId = "sid123",
            accessToken = "at",
            csrfToken = "test-csrf-token",
            claims = listOf(
                KbffClaim("name", "John Doe"),
                KbffClaim("email", "john@example.com"),
                KbffClaim("role", "user")
            ),
            userInfoClaims = listOf(
                KbffClaim("role", "admin"),
                KbffClaim("name", "John Doe") // Duplicate
            )
        )

        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
        }

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration().apply {
                security {
                    enableCsrf = false
                }
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                    serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = session
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = client.get("/bff/user") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.body<kotlinx.serialization.json.JsonObject>()
        val claims = body["claims"] as JsonArray
        expectThat(claims).hasSize(4) // name, email, role (user), role (admin)

        val nameClaims = claims.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "name" }
        expectThat(nameClaims).hasSize(1)
        expectThat(nameClaims[0].jsonObject["value"]?.jsonPrimitive?.content).isEqualTo("John Doe")

        val roleClaims = claims.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "role" }
        expectThat(roleClaims).hasSize(2)
        val roleValues = roleClaims.map { it.jsonObject["value"]?.jsonPrimitive?.content }.toSet()
        expectThat(roleValues).contains("user", "admin")

        expectThat(body["csrfToken"]?.jsonPrimitive?.content).isEqualTo(session.csrfToken)
    }

    @Test
    fun `test logout`() = testApplication {
        val oidcService = mockk<OidcService>()
        coEvery { oidcService.getLogoutUrl(any()) } returns "https://auth.com/logout".right()

        application {
            install(ContentNegotiation) {
                json()
            }
            val config = KbffConfiguration()
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                })
            }
            val session = KbffSession("sid", csrfToken = "csrf-token")
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                   serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = session
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffAuthRoutes(oidcService, config)
            }
        }

        val response = createClient { followRedirects = false }.post("/bff/logout") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header("X-CSRF", "csrf-token")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Found)
        expectThat(response.headers[HttpHeaders.Location]).isEqualTo("https://auth.com/logout")
    }
}
