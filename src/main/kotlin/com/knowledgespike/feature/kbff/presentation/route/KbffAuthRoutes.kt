package com.knowledgespike.feature.kbff.presentation.route

import arrow.core.getOrElse
import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.KbffErrorResponse
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.domain.service.OidcService
import com.knowledgespike.feature.kbff.presentation.auth.verifyCsrfToken
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.knowledgespike.feature.kbff.presentation.route.KbffAuthRoutes")

fun Route.kbffAuthRoutes(
    oidcService: OidcService,
    configuration: KbffConfiguration,
    loginPath: String = "/bff/login",
    callbackPath: String = "/signin-oidc",
    logoutPath: String = "/bff/logout"
) {

    route(loginPath) {
        get {
            val requestedReturnUrl = call.parameters["returnUrl"]
            if (isUnsafeReturnUrl(requestedReturnUrl, callbackPath)) {
                logger.warn("Rejected unsafe returnUrl during login")
            }
            val returnUrl = normalizeReturnUrl(
                returnUrl = requestedReturnUrl,
                callbackPath = callbackPath
            )
            logger.debug("Handling login request, returnUrl: {}", returnUrl)
            val state = oidcService.generateState()
            val nonce = oidcService.generateNonce()
            val codeVerifier = oidcService.generateCodeVerifier()
            val codeChallenge = oidcService.generateCodeChallenge(codeVerifier)

            val initialSession = KbffSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                state = state,
                nonce = nonce,
                codeVerifier = codeVerifier,
                returnUrl = returnUrl,
                csrfToken = oidcService.generateState()
            )
            call.sessions.set(initialSession)

            val authUrl =
                when (val authorizationResult = oidcService.getAuthorizationUrl(state, nonce, codeChallenge)) {
                    is arrow.core.Either.Left -> {
                        call.respondOidcError(authorizationResult.value)
                        return@get
                    }

                    is arrow.core.Either.Right -> authorizationResult.value
                }
            call.respondRedirect(authUrl)
        }
    }

    route(callbackPath) {
        get {
            val code = call.parameters["code"]
            val state = call.parameters["state"]
            val session = call.sessions.get<KbffSession>()

            logger.debug("Handling callback request, state: {}", state)

            if (code == null || state == null || session == null || state != session.state) {
                logger.warn(
                    "Invalid callback or session. code: {}, state: {}, session exists: {}, state match: {}",
                    code != null, state != null, session != null, state == session?.state
                )
                call.respond(
                    HttpStatusCode.BadRequest,
                    KbffErrorResponse("invalid_request", "Invalid callback or session")
                )
                return@get
            }

            val codeVerifier = session.codeVerifier
            if (codeVerifier == null) {
                logger.error("Missing code verifier in session for state: {}", state)
                call.respond(
                    HttpStatusCode.BadRequest,
                    KbffErrorResponse("invalid_session", "Missing code verifier in session")
                )
                return@get
            }

            val updatedSession =
                when (val tokenResult = oidcService.exchangeCodeForTokens(code, codeVerifier, session)) {
                    is arrow.core.Either.Left -> {
                        logger.error("Token exchange failed for state: {}", state)
                        call.respondOidcError(tokenResult.value)
                        return@get
                    }

                    is arrow.core.Either.Right -> tokenResult.value
                }

            val finalSession = if (updatedSession.accessToken != null) {
                val userInfoClaims = oidcService.getUserInfoClaims(updatedSession.accessToken).getOrElse { emptyList() }
                updatedSession.copy(userInfoClaims = userInfoClaims, csrfToken = oidcService.generateState())
            } else {
                updatedSession.copy(csrfToken = oidcService.generateState())
            }

            val redirectUrl = normalizeReturnUrl(
                returnUrl = session.returnUrl,
                callbackPath = callbackPath
            )
            logger.info("Successfully authenticated user, redirecting to: {}", redirectUrl)
            call.sessions.set(finalSession)
            call.respondRedirect(redirectUrl)
        }
    }

    route(logoutPath) {
        post {
            logger.debug("Handling logout request")
            if (!call.verifyCsrfToken(configuration)) {
                logger.warn("Logout failed: Missing or invalid CSRF header")
                call.respond(HttpStatusCode.Forbidden, KbffErrorResponse("forbidden", "Missing or invalid CSRF header"))
                return@post
            }
            val session = call.sessions.get<KbffSession>()
            val logoutUrl = when (val logoutResult = oidcService.getLogoutUrl(session?.idToken)) {
                is arrow.core.Either.Left -> {
                    call.respondOidcError(logoutResult.value)
                    return@post
                }

                is arrow.core.Either.Right -> logoutResult.value
            }
            logger.info("Logging out user, session exists: {}", session != null)
            call.sessions.clear<KbffSession>()
            if (logoutUrl != null) {
                call.respondRedirect(logoutUrl)
            } else {
                call.respondRedirect("/")
            }
        }
    }

    route("/bff/user") {
        get {
            val session = call.requireAuthenticatedSession() ?: return@get

            val mergedClaims = (session.claims + session.userInfoClaims).distinctBy { it.type to it.value }

            val claimsArray = buildJsonObject {
                put("claims", buildJsonArray {
                    mergedClaims.forEach { claim ->
                        add(buildJsonObject {
                            put("type", claim.type)
                            put("value", claim.value)
                            put("valueType", claim.valueType)
                        })
                    }
                })
                put("csrfToken", session.csrfToken)
            }
            call.respond(claimsArray)
        }
    }
}
