package com.knowledgespike.feature.kbff.presentation.auth

import com.knowledgespike.feature.kbff.domain.model.KbffConfiguration
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.knowledgespike.feature.kbff.presentation.auth.KbffSecurity")

/**
 * Installs recommended security headers based on the provided configuration.
 */
@Suppress("unused")
fun Application.installKbffSecurityHeaders(configuration: KbffConfiguration) {
    val security = configuration.security

    if (security.hsts) {
        install(HSTS) {
            includeSubDomains = security.hstsIncludeSubdomains
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        if (security.csp.isNotEmpty()) {
            call.response.header("Content-Security-Policy", security.csp)
        }
        if (security.frameOptions.isNotEmpty()) {
            call.response.header("X-Frame-Options", security.frameOptions)
        }
        if (security.contentTypeOptions.isNotEmpty()) {
            call.response.header("X-Content-Type-Options", security.contentTypeOptions)
        }
        security.permissionsPolicy?.let {
            if (it.isNotEmpty()) {
                call.response.header("Permissions-Policy", it)
            }
        }
        call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
}

/**
 * Verifies the presence of a custom CSRF header for non-GET requests.
 */
fun ApplicationCall.verifyCsrfToken(configuration: KbffConfiguration): Boolean {
    val security = configuration.security

    if (!security.enableCsrf) {
        return true
    }

    if (request.httpMethod == HttpMethod.Get || request.httpMethod == HttpMethod.Head || request.httpMethod == HttpMethod.Options) {
        return true
    }

    val csrfHeader = request.headers[security.csrfHeaderName]
    val session = sessions.get<KbffSession>()
    logger.debug("Getting  session: {}", session)

    // just need the header I don't care what the value is
    if (csrfHeader.isNullOrBlank()) {
        logger.warn(
            "CSRF verification failed: Missing header '{}', sessionCsrf: '{}'",
            security.csrfHeaderName,
            csrfHeader
        )
        return false
    }
    return true
}
