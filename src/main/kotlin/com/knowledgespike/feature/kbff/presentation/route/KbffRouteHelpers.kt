package com.knowledgespike.feature.kbff.presentation.route

import com.knowledgespike.feature.kbff.domain.model.InvalidTokenResponseError
import com.knowledgespike.feature.kbff.domain.model.KbffErrorResponse
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.domain.model.MetadataUnavailableError
import com.knowledgespike.feature.kbff.domain.model.NetworkError
import com.knowledgespike.feature.kbff.domain.model.OidcFlowError
import com.knowledgespike.feature.kbff.domain.model.ParseValidationError
import com.knowledgespike.feature.kbff.domain.model.ValidationError
import com.knowledgespike.feature.kbff.domain.util.UriUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.net.URI

/**
 * Normalizes the return URL by ensuring it does not point to a callback path.
 * If the provided return URL is null or matches the callback path, a fallback URL
 * is generated using the provided scheme, host, and port.
 *
 * @param returnUrl The candidate return URL that should be evaluated and potentially
 *        replaced with a normalized version. Can be null.
 * @param host The hostname to use for generating the fallback URL if normalization is required.
 * @param port The port to use for generating the fallback URL if normalization is required.
 * @param callbackPath The path of the callback endpoint used to determine if the
 *        candidate return URL should be replaced with a fallback URL.
 * @param scheme The scheme (protocol) to use for generating the fallback URL if
 *        normalization is required. Defaults to "http".
 * @return The normalized return URL. The original return URL is returned if it does not
 *         match the callback path and is non-null. Otherwise, the fallback URL is returned.
 */
internal fun normalizeReturnUrl(
    returnUrl: String?,
    host: String,
    port: Int,
    callbackPath: String,
    scheme: String = "http"
): String {
    val fallback = UriUtils.buildAuthorityUrl(scheme, host, port)
    val candidate = returnUrl ?: fallback
    return if (isCallbackUrl(candidate, callbackPath)) fallback else candidate
}


private fun isCallbackUrl(returnUrl: String, callbackPath: String): Boolean {
    if (returnUrl == callbackPath) {
        return true
    }

    return runCatching {
        URI(returnUrl).path == callbackPath
    }.getOrDefault(false)
}

internal suspend fun ApplicationCall.requireAuthenticatedSession(): KbffSession? {
    val session = sessions.get<KbffSession>()
    if (session?.accessToken == null) {
        respondUnauthorized()
        return null
    }
    return session
}

internal suspend fun ApplicationCall.respondUnauthorized() {
    respond(HttpStatusCode.Unauthorized)
}

internal suspend fun ApplicationCall.respondCsrfRejected() {
    respond(HttpStatusCode.Forbidden, KbffErrorResponse("forbidden", "Missing or invalid CSRF header"))
}

internal suspend fun ApplicationCall.respondOidcError(error: OidcFlowError) {
    val (status, code) = when (error) {
        is NetworkError -> HttpStatusCode.BadGateway to "oidc_network_failure"
        is MetadataUnavailableError -> HttpStatusCode.BadGateway to "oidc_metadata_unavailable"
        is InvalidTokenResponseError -> HttpStatusCode.Unauthorized to "authentication_failed"
        is ParseValidationError -> HttpStatusCode.BadRequest to "oidc_parse_validation_failure"
        is ValidationError -> HttpStatusCode.BadRequest to "oidc_validation_failure"
    }
    respond(status, KbffErrorResponse(code, error.message))
}