package com.knowledgespike.feature.kbff.domain.model

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.knowledgespike.feature.kbff.domain.model.Configuration")

@Serializable
data class OidcConfiguration(
    val authority: String = "",
    val discoveryUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: List<String> = listOf("openid", "profile"),
    val redirectUri: String = "",
    val postLogoutRedirectUri: String = "",
    val sslTrustAll: Boolean = false,
    val sslCertificatePath: String? = null,
    val connectTimeoutMillis: Long = 5000L,
    val socketTimeoutMillis: Long = 5000L
)

@Serializable
data class SecurityConfiguration(
    val enableCsrf: Boolean = true,
    val csrfHeaderName: String = "X-CSRF",
    val hsts: Boolean = true,
    val hstsIncludeSubdomains: Boolean = true,
    val csp: String = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; form-action 'self';",
    val frameOptions: String = "DENY",
    val contentTypeOptions: String = "nosniff",
    val permissionsPolicy: String? = null
)

@Serializable
data class ProxyEndpoint(val path: String, val targetUrl: String)

@Serializable
data class ProxyConfiguration(val endpoints: List<ProxyEndpoint> = emptyList())

class OidcConfigurationBuilder {
    var authority: String = ""
    var discoveryUrl: String = ""
    var clientId: String = ""
    var clientSecret: String = ""
    var scopes: List<String> = listOf("openid", "profile")
    var redirectUri: String = ""
    var postLogoutRedirectUri: String = ""
    var sslTrustAll: Boolean = false
    var sslCertificatePath: String? = null
    var connectTimeoutMillis: Long = 5000L
    var socketTimeoutMillis: Long = 5000L

    fun build() = OidcConfiguration(
        authority,
        discoveryUrl,
        clientId,
        clientSecret,
        scopes,
        redirectUri,
        postLogoutRedirectUri,
        sslTrustAll,
        sslCertificatePath,
        connectTimeoutMillis,
        socketTimeoutMillis
    )
}

class SecurityConfigurationBuilder {
    var enableCsrf: Boolean = true
    var csrfHeaderName: String = "X-CSRF"
    var hsts: Boolean = true
    var hstsIncludeSubdomains: Boolean = true
    var csp: String = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; form-action 'self';"
    var frameOptions: String = "DENY"
    var contentTypeOptions: String = "nosniff"
    var permissionsPolicy: String? = null

    fun build() = SecurityConfiguration(
        enableCsrf,
        csrfHeaderName,
        hsts,
        hstsIncludeSubdomains,
        csp,
        frameOptions,
        contentTypeOptions,
        permissionsPolicy
    )
}

class ProxyConfigurationBuilder {
    private val endpoints = mutableListOf<ProxyEndpoint>()

    fun endpoint(path: String, targetUrl: String) {
        endpoints.add(ProxyEndpoint(path, targetUrl))
    }

    fun build() = ProxyConfiguration(endpoints.toList())
}

@Serializable
class KbffConfiguration {
    var oidc = OidcConfiguration()
        private set
    var proxy = ProxyConfiguration()
        private set
    var security = SecurityConfiguration()
        private set

    fun oidc(block: OidcConfigurationBuilder.() -> Unit) {
        val builder = OidcConfigurationBuilder()
        builder.block()
        this.oidc = builder.build()
    }

    fun security(block: SecurityConfigurationBuilder.() -> Unit) {
        val builder = SecurityConfigurationBuilder()
        builder.block()
        this.security = builder.build()
        logger.debug("Security configuration built: {}", this.security)
    }

    fun proxy(block: ProxyConfigurationBuilder.() -> Unit) {
        val builder = ProxyConfigurationBuilder()
        builder.block()
        this.proxy = builder.build()
    }
}
