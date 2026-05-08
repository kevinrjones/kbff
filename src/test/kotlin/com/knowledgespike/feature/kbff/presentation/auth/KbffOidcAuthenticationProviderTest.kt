package com.knowledgespike.feature.kbff.presentation.auth

import com.knowledgespike.feature.kbff.domain.model.KbffClaim
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KbffOidcAuthenticationProviderTest {

    @Test
    fun `returns unauthorized when session is missing`() = testApplication {
        application {
            install(Authentication) {
                kbffOidc("kbff") {
                    redirectToLogin(loginPath = "/bff/login", callbackPath = "/signin-oidc")
                }
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                authenticate("kbff") {
                    get("/user") {
                        val principal = call.principal<KbffUserPrincipal>()
                        call.respond(principal?.name ?: "missing")
                    }
                }
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/user")

        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertTrue(location != null)
        assertTrue(location.contains("/bff/login?"))
        assertTrue(location.contains("returnUrl="))
        assertTrue(location.contains("%2Fuser"))
    }

    @Test
    fun `redirect includes original query parameters`() = testApplication {
        application {
            install(Authentication) {
                kbffOidc("kbff") {
                    redirectToLogin(loginPath = "/bff/login", callbackPath = "/signin-oidc")
                }
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                authenticate("kbff") {
                    get("/user") {
                        call.respond("ok")
                    }
                }
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/user?tab=claims")
        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertTrue(location != null)
        assertTrue(location.contains("%2Fuser%3Ftab%3Dclaims"))
    }

    @Test
    fun `redirect includes non-standard port from request host`() = testApplication {
        application {
            install(Authentication) {
                kbffOidc("kbff") {
                    redirectToLogin(loginPath = "/bff/login", callbackPath = "/signin-oidc")
                }
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                authenticate("kbff") {
                    get("/user") {
                        call.respond("ok")
                    }
                }
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/user") {
            header(HttpHeaders.Host, "localhost:44410")
        }

        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertTrue(location != null)
        assertTrue(location.contains("returnUrl=http%3A%2F%2Flocalhost%3A44410%2Fuser"))
    }

    @Test
    fun `redirect uses request non-standard port when login path is absolute without port`() = testApplication {
        application {
            install(Authentication) {
                kbffOidc("kbff") {
                    redirectToLogin(loginPath = "http://localhost/bff/login", callbackPath = "/signin-oidc")
                }
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                authenticate("kbff") {
                    get("/user") {
                        call.respond("ok")
                    }
                }
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/user") {
            header(HttpHeaders.Host, "localhost:44410")
        }

        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertEquals(
            "http://localhost:44410/bff/login?returnUrl=http%3A%2F%2Flocalhost%3A44410%2Fuser",
            location
        )
    }
    
    @Test
    fun `redirect includes non-standard port for relative login path`() = testApplication {
        application {
            install(Authentication) {
                kbffOidc("kbff") {
                    redirectToLogin(loginPath = "/bff/login", callbackPath = "/signin-oidc")
                }
            }
            install(Sessions) {
                cookie<KbffSession>("KBFF_SESSION")
            }
            routing {
                authenticate("kbff") {
                    get("/user") {
                        call.respond("ok")
                    }
                }
            }
        }

        val client = createClient {
            followRedirects = false
        }
        val response = client.get("/user") {
            header(HttpHeaders.Host, "localhost:44410")
        }

        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertEquals(
            "http://localhost:44410/bff/login?returnUrl=http%3A%2F%2Flocalhost%3A44410%2Fuser",
            location
        )
    }

    @Test
    fun `returns principal when session contains access token`() = testApplication {
        val session = KbffSession(
            sessionId = "sid-1",
            accessToken = "access-token",
            claims = listOf(
                KbffClaim("name", "Jane Doe"),
                KbffClaim("email", "jane@example.com")
            )
        )

        application {
            install(Authentication) {
                kbffOidc("kbff")
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
                authenticate("kbff") {
                    get("/user") {
                        val principal = call.principal<KbffUserPrincipal>()
                        val email = principal?.claims?.find { it.type == "email" }?.value
                        call.respond("${principal?.name}:$email")
                    }
                }
            }
        }

        val response = client.get("/user") {
            header(HttpHeaders.Cookie, "KBFF_SESSION=mock-session")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Jane Doe:jane@example.com"))
    }
}