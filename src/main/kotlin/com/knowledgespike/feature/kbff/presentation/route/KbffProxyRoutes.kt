package com.knowledgespike.feature.kbff.presentation.route

import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.domain.service.OidcService
import com.knowledgespike.feature.kbff.domain.util.UriUtils
import com.knowledgespike.feature.kbff.presentation.auth.verifyCsrfToken
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import io.ktor.utils.io.*
import org.slf4j.LoggerFactory
import java.io.IOException

private val logger = LoggerFactory.getLogger("com.knowledgespike.feature.kbff.presentation.route.KbffProxyRoutes")

fun Route.kbffProxyRoutes(
    configuration: KbffConfiguration,
    httpClient: HttpClient,
    oidcService: OidcService,
) {
    configuration.proxy.endpoints.forEach { endpoint ->
        val path = endpoint.path.removePrefix("/")
        route("/$path/{proxy...}") {
            handle {
                if (!call.verifyCsrfToken(configuration)) {
                    logger.warn("Proxy request to {} failed: Missing or invalid CSRF header", call.request.uri)
                    call.respondCsrfRejected()
                    return@handle
                }

                val session = call.requireAuthenticatedSession() ?: return@handle
                val currentSession = ensureValidSession(call, session, oidcService)

                val targetUrl = buildTargetUrl(endpoint.targetUrl, call)

                val trustedHosts = configuration.proxy.endpoints.mapNotNull {
                    try {
                        java.net.URI(it.targetUrl).host
                    } catch (e: Exception) {
                        null
                    }
                }.distinct()

                if (!UriUtils.isTrustedInternalTarget(targetUrl.toString(), trustedHosts)) {
                    logger.error("Blocking potentially malicious proxy target: {}", targetUrl)
                    call.respond(HttpStatusCode.Forbidden, "Untrusted proxy target")
                    return@handle
                }

                logger.debug("Proxying {} request to {}", call.request.httpMethod.value, targetUrl)

                val proxiedResponse = try {
                    executeProxyRequest(httpClient, targetUrl, call, currentSession)
                } catch (exception: IOException) {
                    logger.error(
                        "Proxy request failed for {} {} to {}: {}",
                        call.request.httpMethod.value,
                        call.request.uri,
                        targetUrl,
                        exception.message,
                        exception
                    )
                    call.respond(
                        HttpStatusCode.BadGateway,
                        "Downstream API unavailable while proxying ${call.request.uri}"
                    )
                    return@handle
                }

                forwardResponseHeaders(proxiedResponse, call)

                call.respondBytesWriter(status = proxiedResponse.status) {
                    val channel = proxiedResponse.bodyAsChannel()
                    channel.copyTo(this)
                }
            }
        }
    }
}

/**
 * Ensures that the provided session is valid, refreshing the access token if necessary.
 * If the token is close to expiration or already expired and a refresh token is available,
 * this method attempts to refresh the session and updates it in the current call.
 *
 * @param call The [ApplicationCall] representing the current context of the HTTP call.
 * @param session The current [KbffSession], containing session and token details.
 * @param oidcService The [OidcService] used to handle token refresh operations.
 * @param window The time window in milliseconds to determine if a token is considered close to expiration.
 *               Defaults to 10000 milliseconds if not specified.
 * @return The updated [KbffSession] if the session was refreshed successfully, or the original session if no refresh occurred.
 */
private suspend fun ensureValidSession(
    call: ApplicationCall,
    session: KbffSession,
    oidcService: OidcService,
    window: Int = 10000,
): KbffSession {
    var currentSession = session

    val expiresAt = currentSession.expiresAt
    if (expiresAt != null && expiresAt < System.currentTimeMillis() + window) {
        val refreshToken = currentSession.refreshToken
        if (refreshToken != null) {
            logger.info("Access token expired or about to expire, attempting refresh")
            val refreshedSession = oidcService.refreshTokens(refreshToken, currentSession).fold(
                ifLeft = { error ->
                    logger.error("Token refresh failed during proxy request: {}", error.message)
                    null
                },
                ifRight = { session -> session }
            )
            if (refreshedSession != null) {
                call.sessions.set(refreshedSession)
                currentSession = refreshedSession
            }
        } else {
            logger.warn("Access token expired and no refresh token available")
        }
    }
    return currentSession
}

/**
 * Builds a target URL based on the provided base URL and request details from the ApplicationCall.
 *
 * Combines the base URL with the path and query parameters extracted from the ApplicationCall.
 * If the "proxy" parameter is present in the call, its values will be joined and appended to the base URL's path.
 * Additionally, all query parameters from the request are appended to the URL.
 *
 * @param targetBaseUrl The base URL to which the proxy path and query parameters will be appended.
 * @param call The ApplicationCall containing the incoming HTTP request details, including path and query parameters.
 * @return A constructed Url object representing the combined target URL.
 */
private fun buildTargetUrl(targetBaseUrl: String, call: ApplicationCall): Url {
    val remainingPath = call.parameters.getAll("proxy")?.joinToString("/") ?: ""
    return URLBuilder(targetBaseUrl).apply {
        if (remainingPath.isNotEmpty()) {
            val currentPath = encodedPath.removeSuffix("/")
            val newPath = remainingPath.removePrefix("/")
            encodedPath = "$currentPath/$newPath"
        }
        parameters.appendAll(call.request.queryParameters)
    }.build()
}

/**
 * Executes a proxy request by forwarding the incoming `ApplicationCall` to the specified target URL,
 * transferring headers, body, and method while adding an authorization header using the given session's access token.
 *
 * @param httpClient The `HttpClient` instance used to prepare and execute the request.
 * @param targetUrl The `Url` to which the incoming request should be proxied.
 * @param call The incoming `ApplicationCall` that contains the headers, method, and body to forward.
 * @param session The `KbffSession` containing the access token used for authorization.
 * @return The `HttpResponse` returned by the proxied request.
 */
private suspend fun executeProxyRequest(
    httpClient: HttpClient,
    targetUrl: Url,
    call: ApplicationCall,
    session: KbffSession,
): HttpResponse {
    return httpClient.prepareRequest {
        method = call.request.httpMethod
        url(targetUrl)
        headers {
            appendAll(call.request.headers.filter { key, _ ->
                !key.equals(HttpHeaders.Host, ignoreCase = true)
            })
            append(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
        }
        setBody(call.request.receiveChannel())
    }.execute()
}

/**
 * Forwards a subset of response headers from a proxied HTTP response to the current application call response.
 *
 * @param proxiedResponse The HttpResponse object representing the response from the proxied server.
 * @param call The ApplicationCall representing the current client-server interaction within the application.
 */
private fun forwardResponseHeaders(proxiedResponse: HttpResponse, call: ApplicationCall) {
    proxiedResponse.headers.forEach { key, values ->
        if (FORWARDED_RESPONSE_HEADERS.contains(key.lowercase())) {
            values.forEach { value ->
                call.response.headers.append(key, value)
            }
        }
    }
}

private val FORWARDED_RESPONSE_HEADERS = setOf(
    HttpHeaders.ContentType.lowercase(),
    HttpHeaders.ContentEncoding.lowercase(),
    HttpHeaders.CacheControl.lowercase(),
    HttpHeaders.ETag.lowercase(),
    HttpHeaders.LastModified.lowercase(),
    HttpHeaders.Expires.lowercase(),
    HttpHeaders.ContentDisposition.lowercase(),
    HttpHeaders.Vary.lowercase()
)
