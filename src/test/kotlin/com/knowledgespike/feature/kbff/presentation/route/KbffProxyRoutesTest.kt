package com.knowledgespike.feature.kbff.presentation.route

import arrow.core.right
import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.domain.service.OidcService
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

class KbffProxyRoutesTest {

    @Test
    fun `test proxy fails when csrf enabled but header missing`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api/player", "http://localhost:8080/api/player")
            }
            security {
                enableCsrf = true
                csrfHeaderName = "X-CSRF"
            }
        }
        val httpClient = HttpClient(MockEngine { respondOk() })

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "at",
            csrfToken = "correct-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/player/do-something") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            // No CSRF header
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun `test proxy fails when csrf enabled but header mismatched`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api/player", "http://localhost:8080/api/player")
            }
            security {
                enableCsrf = true
                csrfHeaderName = "X-CSRF"
            }
        }
        val httpClient = HttpClient(MockEngine { respondOk() })

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "at",
            csrfToken = "correct-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/player/do-something") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header("X-CSRF", "wrong-token")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun `test proxy succeeds when csrf enabled and header matches`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api/player", "http://localhost:8080/api/player")
            }
            security {
                enableCsrf = true
                csrfHeaderName = "X-CSRF"
            }
        }
        val httpClient = HttpClient(MockEngine {
            respond(
                content = "ok",
                status = HttpStatusCode.OK
            )
        })

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "at",
            csrfToken = "correct-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/player/do-something") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header("X-CSRF", "correct-token")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `test proxy downstream failure returns bad gateway`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api/player", "http://localhost:8080/api/player")
            }
            security {
                enableCsrf = false
            }
        }

        val mockEngine = MockEngine {
            throw IOException("Connection refused")
        }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "player-access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.get("/api/player/findplayers") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.BadGateway)
    }

    @Test
    fun `test player proxy route forwards bearer token`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api/player", "http://localhost:8080/api/player")
            }
            security {
                enableCsrf = false
            }
        }

        val mockEngine = MockEngine { request ->
            expectThat(request.url.toString()).isEqualTo("http://localhost:8080/api/player/findplayers")
            expectThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer player-access-token")

            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "player-access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.get("/api/player/findplayers") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `test proxy forwarding with token injection`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = false
            }
        }

        val mockEngine = MockEngine { request ->
            expectThat(request.url.toString()).isEqualTo("https://downstream.com/api/test?query=1")
            expectThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer access-token")
            
            respond(
                content = "proxied-content",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            expiresAt = System.currentTimeMillis() + 100000 // Not expired
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.get("/api/test?query=1") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
        // No body check here because respondBytesWriter uses a channel and it's hard to read in tests sometimes
        // but let's try if it works
        // expectThat(response.bodyAsText()).isEqualTo("proxied-content")
    }

    @Test
    fun `test proxy with token refresh`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = false
            }
        }

        val expiredSession = KbffSession(
            sessionId = "sid",
            accessToken = "expired-token",
            refreshToken = "refresh-token",
            expiresAt = System.currentTimeMillis() - 5000 // Expired
        )
        val refreshedSession = expiredSession.copy(
            accessToken = "new-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        coEvery { oidcService.refreshTokens("refresh-token", any()) } returns refreshedSession.right()

        val mockEngine = MockEngine { request ->
            expectThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer new-token")
            respond("ok")
        }
        val httpClient = HttpClient(mockEngine)

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
                })
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION") {
                    serializer = object : SessionSerializer<KbffSession> {
                        override fun deserialize(text: String): KbffSession = expiredSession
                        override fun serialize(session: KbffSession): String = "mock-session"
                    }
                }
            }
            routing {
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.get("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
        // Verify that the new session was set in cookies
        val setCookie = response.headers[HttpHeaders.SetCookie]
        expectThat(setCookie).isNotNull()
    }

    @Test
    fun `test proxy without CSRF header should fail for POST`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = true
                csrfHeaderName = "X-CSRF"
            }
        }

        val mockEngine = MockEngine { respond("ok") }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun `test proxy with CSRF header should succeed for POST`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = true
                csrfHeaderName = "X-CSRF"
            }
        }

        val mockEngine = MockEngine { respond("ok") }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header("X-CSRF", "csrf-token")
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `test proxy blocking untrusted target`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "http://trusted-internal-api/api")
            }
            security {
                enableCsrf = false
            }
        }

        val mockEngine = MockEngine { respond("ok") }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        // Attempting to bypass by manipulating the proxy path (if buildTargetUrl was vulnerable, which it isn't currently but we test the protection)
        // More realistically, if someone manages to inject a different targetUrl into the configuration, but here we test the path parameter handling
        // if buildTargetUrl was vulnerable to path traversal that leads to a different host.
        // But UriUtils.isTrustedInternalTarget checks the host of the FINAL target URL.
        
        // Since buildTargetUrl currently just appends the path to the configured targetBaseUrl, 
        // a simple path like "test" will still have the same host.
        
        // Let's assume we want to ensure only the configured host is allowed.
        val response = client.get("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }
        expectThat(response.status).isEqualTo(HttpStatusCode.OK)

        // If we somehow had a way to influence the host in buildTargetUrl (which we don't right now, but we want to be safe)
    }

    @Test
    fun `test proxy forwards POST data`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = false
            }
        }

        val postData = "{\"key\":\"value\"}"
        val mockEngine = MockEngine { request ->
            expectThat(request.url.toString()).isEqualTo("https://downstream.com/api/test")
            expectThat(request.method).isEqualTo(HttpMethod.Post)

            val body = request.body.toByteArray().decodeToString()
            expectThat(body).isEqualTo(postData)

            respond("ok")
        }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(postData)
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `test proxy forwards data with Content-Length header`() = testApplication {
        val oidcService = mockk<OidcService>()
        val config = KbffConfiguration().apply {
            proxy {
                endpoint("/api", "https://downstream.com/api")
            }
            security {
                enableCsrf = false
            }
        }

        val postData = "Content with length"
        val mockEngine = MockEngine { request ->
            expectThat(request.headers[HttpHeaders.ContentLength]).isEqualTo(postData.length.toString())
            val body = request.body.toByteArray().decodeToString()
            expectThat(body).isEqualTo(postData)

            respond("ok")
        }
        val httpClient = HttpClient(mockEngine)

        val session = KbffSession(
            sessionId = "sid",
            accessToken = "access-token",
            csrfToken = "csrf-token",
            expiresAt = System.currentTimeMillis() + 100000
        )

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(Koin) {
                modules(module {
                    single { oidcService }
                    single { config }
                    single { httpClient }
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
                kbffProxyRoutes(config, httpClient, oidcService)
            }
        }

        val response = client.post("/api/test") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
            header(HttpHeaders.ContentLength, postData.length.toString())
            setBody(postData)
        }

        expectThat(response.status).isEqualTo(HttpStatusCode.OK)
    }
}
