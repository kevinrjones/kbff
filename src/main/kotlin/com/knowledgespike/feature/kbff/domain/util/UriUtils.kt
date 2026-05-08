package com.knowledgespike.feature.kbff.domain.util

import java.net.URI

object UriUtils {
    /**
     * Builds an authority-only URL (scheme://host[:port]/) from the provided components,
     * suppressing the port if it's the default for the given scheme.
     */
    fun buildAuthorityUrl(scheme: String, host: String, port: Int): String {
        val isStandardPort = isStandardPort(scheme, port)
        return if (isStandardPort) "$scheme://$host/" else "$scheme://$host:$port/"
    }

    /**
     * Checks if the given port is the standard port for the given scheme.
     */
    fun isStandardPort(scheme: String, port: Int): Boolean {
        return (scheme.equals("https", ignoreCase = true) && port == 443) ||
                (scheme.equals("http", ignoreCase = true) && port == 80)
    }

    /**
     * Checks if a target URL's host is considered internal or trusted.
     * This is a basic SSRF protection check.
     * In a production environment, this should be configurable with an allow-list.
     */
    fun isTrustedInternalTarget(targetUrl: String, trustedHosts: List<String>): Boolean {
        return try {
            val uri = URI(targetUrl)
            val host = uri.host ?: return false
            trustedHosts.any { it.equals(host, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }
}
