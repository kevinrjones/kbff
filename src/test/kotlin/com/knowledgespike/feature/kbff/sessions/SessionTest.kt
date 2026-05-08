package com.knowledgespike.feature.kbff.sessions

import com.knowledgespike.feature.kbff.data.repository.InMemoryKbffSessionStorage
import com.knowledgespike.feature.kbff.domain.model.KbffClaim
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.*

class SessionTest {

    @Test
    fun testSessionCookieAttributes() = testApplication {
        application {
            install(Sessions) {
                cookie<KbffSession>("kbff_session", InMemoryKbffSessionStorage()) {
                    cookie.path = "/"
                    cookie.httpOnly = true
                    cookie.secure = true
                    cookie.extensions["SameSite"] = "Lax"
                }
            }
            routing {
                get("/set-session") {
                    call.sessions.set(KbffSession("test-id", claims = listOf(KbffClaim("user", "admin"))))
                    call.respond(HttpStatusCode.OK)
                }
                get("/get-session") {
                    val session = call.sessions.get<KbffSession>()
                    val user = session?.claims?.find { it.type == "user" }?.value
                    call.respond(user ?: "none")
                }
            }
        }

        val client = createClient { }

        val response = client.get("/set-session")
        assertEquals(HttpStatusCode.OK, response.status)

        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        assertTrue(setCookie.contains("HttpOnly"))
        assertTrue(setCookie.contains("Secure"))
        assertTrue(setCookie.contains("SameSite=Lax"))

        val response2 = client.get("/get-session") {
            header(HttpHeaders.Cookie, setCookie.substringBefore(";"))
        }
        assertEquals("admin", response2.bodyAsText())
    }
}
