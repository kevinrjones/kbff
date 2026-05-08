package com.knowledgespike.feature.kbff.domain.service

import arrow.core.*
import com.knowledgespike.feature.kbff.domain.model.*
import com.knowledgespike.feature.kbff.domain.model.ValidationError
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenResponse
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.SecureRandom
import java.util.*

class OidcService(
    private val httpClient: HttpClient,
    private val configuration: KbffConfiguration
) {
    private val logger = LoggerFactory.getLogger(OidcService::class.java)
    private val secureRandom = SecureRandom()
    private var metadataCache: OIDCProviderMetadata? = null
    private var metadataFetchedAt: Long = 0L
    private val metadataTtlMillis = 300_000L

    fun generateState(): String = generateRandomString(32)
    fun generateNonce(): String = generateRandomString(32)
    fun generateCodeVerifier(): String = CodeVerifier().value

    fun generateCodeChallenge(codeVerifier: String): String {
        return CodeChallenge.compute(CodeChallengeMethod.S256, CodeVerifier(codeVerifier)).value
    }

    private fun generateRandomString(length: Int): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    suspend fun getAuthorizationUrl(
        state: String,
        nonce: String,
        codeChallenge: String
    ): Either<OidcFlowError, String> {
        val metadata = when (val metadataResult = getProviderMetadata()) {
            is Either.Left -> return metadataResult.value.left()
            is Either.Right -> metadataResult.value
        }
        val oidc = configuration.oidc
        val pushedRequestUri = createPushedAuthorizationRequest(metadata, state, nonce, codeChallenge)

        if (pushedRequestUri != null) {
            return URLBuilder(metadata.authorizationEndpointURI.toString()).apply {
                parameters.append("client_id", oidc.clientId)
                parameters.append("request_uri", pushedRequestUri)
            }.buildString().right()
        }

        val request = AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope(*oidc.scopes.toTypedArray()),
            ClientID(oidc.clientId),
            URI.create(oidc.redirectUri)
        ).endpointURI(metadata.authorizationEndpointURI)
            .state(State(state))
            .nonce(Nonce(nonce))
            .build()

        return URLBuilder(request.toURI().toString()).apply {
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString().right()
    }

    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        session: KbffSession? = null
    ): Either<OidcFlowError, KbffSession> {
        val oidc = configuration.oidc
        val metadata = when (val metadataResult = getProviderMetadata()) {
            is Either.Left -> return metadataResult.value.left()
            is Either.Right -> metadataResult.value
        }
        val tokenUrl = metadata.tokenEndpointURI.toString()

        logger.debug("Exchanging code for tokens at {}", tokenUrl)

        val tokenResult = executeTokenRequest(
            tokenUrl = tokenUrl,
            metadata = metadata,
            includeClientSecret = oidc.clientSecret.isNotBlank(),
            parameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", oidc.redirectUri)
                append("code_verifier", codeVerifier)
            }
        )
        val (tokenResponse, responseBody) = when (tokenResult) {
            is Either.Left -> return tokenResult.value.left()
            is Either.Right -> tokenResult.value
        }

        if (!tokenResponse.indicatesSuccess()) {
            logger.warn("Token endpoint returned an error during code exchange")
            return InvalidTokenResponseError("Token endpoint returned an error during code exchange").left()
        }

        logger.info("Successfully exchanged code for tokens")
        val json = when (val parsed = parseJson(responseBody)) {
            is Either.Left -> return parsed.value.left()
            is Either.Right -> parsed.value
        }
        logRetrievedTokens("code exchange", json)
        return mapJsonToSession(json, session).right()
    }

    suspend fun refreshTokens(refreshToken: String, session: KbffSession? = null): Either<OidcFlowError, KbffSession> {
        val oidc = configuration.oidc
        val metadata = when (val metadataResult = getProviderMetadata()) {
            is Either.Left -> return metadataResult.value.left()
            is Either.Right -> metadataResult.value
        }
        val tokenUrl = metadata.tokenEndpointURI.toString()

        logger.debug("Refreshing tokens at {}", tokenUrl)

        val tokenResult = executeTokenRequest(
            tokenUrl = tokenUrl,
            metadata = metadata,
            includeClientSecret = oidc.clientSecret.isNotBlank(),
            parameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            }
        )
        val (tokenResponse, responseBody) = when (tokenResult) {
            is Either.Left -> return tokenResult.value.left()
            is Either.Right -> tokenResult.value
        }

        if (!tokenResponse.indicatesSuccess()) {
            logger.warn("Token endpoint returned an error during token refresh")
            return InvalidTokenResponseError("Token endpoint returned an error during token refresh").left()
        }

        logger.info("Successfully refreshed tokens")
        val json = when (val parsed = parseJson(responseBody)) {
            is Either.Left -> return parsed.value.left()
            is Either.Right -> parsed.value
        }
        logRetrievedTokens("token refresh", json)
        return mapJsonToSession(json, session).right()
    }

    private fun maskToken(token: String?): String {
        if (token == null) return "null"
        if (token.length <= 8) return "****"
        return "${token.take(4)}****${token.takeLast(4)}"
    }

    private fun logRetrievedTokens(flow: String, json: JsonObject) {
        val accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
        val idToken = json["id_token"]?.jsonPrimitive?.contentOrNull
        val refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull

        logger.info(
            "Tokens retrieved from identity server during {}. access_token={}, id_token={}, refresh_token={}",
            flow,
            maskToken(accessToken),
            maskToken(idToken),
            maskToken(refreshToken)
        )
    }

    suspend fun getLogoutUrl(idToken: String?): Either<OidcFlowError, String?> {
        val oidc = configuration.oidc
        if (oidc.postLogoutRedirectUri.isEmpty()) return null.right()

        val metadata = when (val metadataResult = getProviderMetadata()) {
            is Either.Left -> return metadataResult.value.left()
            is Either.Right -> metadataResult.value
        }
        val endSessionEndpoint = metadata.endSessionEndpointURI ?: return null.right()
        val postLogout = URI.create(oidc.postLogoutRedirectUri)

        return URLBuilder(endSessionEndpoint.toString()).apply {
            parameters.append("post_logout_redirect_uri", postLogout.toString())
            idToken?.let { parameters.append("id_token_hint", it) }
        }.buildString().right()
    }

    private suspend fun mapJsonToSession(json: JsonObject, existingSession: KbffSession? = null): KbffSession {
        val accessToken = json["access_token"]?.jsonPrimitive?.content
        val idToken = json["id_token"]?.jsonPrimitive?.content
        val refreshToken = json["refresh_token"]?.jsonPrimitive?.content
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

        val claims = idToken?.let {
            parseAndValidateIdToken(it, existingSession?.nonce).getOrElse { error ->
                logger.warn("ID token validation failed: {}", error.message)
                emptyList()
            }
        } ?: emptyList()

        val session = existingSession?.copy() ?: KbffSession(UUID.randomUUID().toString())

        return session.copy(
            accessToken = accessToken,
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + (expiresIn * 1000),
            claims = claims
        )
    }

    suspend fun getUserInfoClaims(accessToken: String): Either<OidcFlowError, List<KbffClaim>> {
        val metadata = when (val metadataResult = getProviderMetadata()) {
            is Either.Left -> return metadataResult.value.left()
            is Either.Right -> metadataResult.value
        }
        val userInfoUrl = metadata.userInfoEndpointURI?.toString() ?: return emptyList<KbffClaim>().right()

        logger.debug("Fetching user info from {}", userInfoUrl)

        return try {
            val response = httpClient.get(userInfoUrl) {
                bearerAuth(accessToken)
            }

            if (response.status != HttpStatusCode.OK) {
                logger.warn("User info request failed with status {}", response.status)
                return emptyList<KbffClaim>().right()
            }

            val body = response.bodyAsText()
            val json = when (val parsed = parseJson(body)) {
                is Either.Left -> return parsed.value.left()
                is Either.Right -> parsed.value
            }

            json.entries.flatMap { (key, element) ->
                when (element) {
                    is JsonArray -> element.map { KbffClaim(key, it.jsonPrimitive.content) }
                    is JsonPrimitive -> listOf(KbffClaim(key, element.content))
                    else -> emptyList()
                }
            }.right()
        } catch (e: Exception) {
            logger.warn("Failed to fetch user info", e)
            emptyList<KbffClaim>().right()
        }
    }

    private suspend fun parseAndValidateIdToken(
        idToken: String,
        nonce: String?
    ): Either<OidcFlowError, List<KbffClaim>> {
        return try {
            val metadata = when (val metadataResult = getProviderMetadata()) {
                is Either.Left -> {
                    logger.warn("Failed to load metadata for ID token validation {}", metadataResult.value)
                    return metadataResult.value.left()
                }

                is Either.Right -> metadataResult.value
            }

            val resourceRetriever: ResourceRetriever? = if (configuration.oidc.sslTrustAll) {
                DefaultResourceRetriever(
                    configuration.oidc.connectTimeoutMillis.toInt(),
                    configuration.oidc.socketTimeoutMillis.toInt(),
                    0,
                    true
                )
            } else null

            val validator = IDTokenValidator(
                metadata.issuer,
                ClientID(configuration.oidc.clientId),
                JWSAlgorithm.RS256,
                metadata.jwkSetURI.toURL(),
                resourceRetriever
            )
            val claims = validator.validate(SignedJWT.parse(idToken), nonce?.let { Nonce(it) })
            claims.toJSONObject().entries.flatMap { (key, value) ->
                when (value) {
                    is Iterable<*> -> value.map { KbffClaim(key, it?.toString() ?: "") }
                    else -> listOf(KbffClaim(key, value?.toString() ?: ""))
                }
            }.right()
        } catch (e: Exception) {
            logger.warn("Failed to validate ID token", e)
            ValidationError("Failed to validate ID token: ${e.message}").left()
        }
    }

    private suspend fun getProviderMetadata(): Either<OidcFlowError, OIDCProviderMetadata> {
        val now = System.currentTimeMillis()
        val cached = metadataCache
        if (cached != null && now - metadataFetchedAt <= metadataTtlMillis) {
            return cached.right()
        }

        val oidc = configuration.oidc
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                val authority = oidc.authority.trim()
                logger.info("Resolving OIDC metadata for authority: '{}'", authority)
                val discoveryUri = oidc.discoveryUrl.takeIf { it.isNotBlank() }?.trim()
                    ?: (if (authority.endsWith("/")) authority else "$authority/").plus(".well-known/openid-configuration")
                logger.info("Discovery URI resolved to: '{}'", discoveryUri)
                val discoveryResponse = httpClient.get(discoveryUri) {
                    timeout {
                        connectTimeoutMillis = oidc.connectTimeoutMillis
                        socketTimeoutMillis = oidc.socketTimeoutMillis
                    }
                }

                if (discoveryResponse.status != HttpStatusCode.OK) {
                    val responsePreview = discoveryResponse.bodyAsText().replace("\n", " ").take(200)
                    logger.error(
                        "OIDC discovery returned status {} for URI {}. Response preview: {}",
                        discoveryResponse.status,
                        discoveryUri,
                        responsePreview
                    )
                    lastError = IllegalStateException("OIDC discovery returned status ${discoveryResponse.status}")
                    return@repeat
                }

                val metadataJson = discoveryResponse.bodyAsText()
                if (metadataJson.isBlank()) {
                    logger.error("OIDC discovery returned empty body for URI {}", discoveryUri)
                    lastError = ParseException("OIDC metadata response was empty")
                    return@repeat
                }

                val discovered = OIDCProviderMetadata.parse(metadataJson)
                metadataCache = discovered
                metadataFetchedAt = now
                return discovered.right()
            } catch (e: ParseException) {
                logger.error("Failed to parse OIDC metadata on attempt ${attempt + 1}", e)
                lastError = e
                return@repeat
            } catch (e: java.net.UnknownHostException) {
                logger.error("Unknown host resolving OIDC metadata on attempt ${attempt + 1}: {}", e.message)
                lastError = e
            } catch (e: java.nio.channels.UnresolvedAddressException) {
                logger.error("Unresolved address resolving OIDC metadata on attempt ${attempt + 1}")
                lastError = e
            } catch (e: java.net.ConnectException) {
                logger.error("Connection refused resolving OIDC metadata on attempt ${attempt + 1}: {}", e.message)
                lastError = e
            } catch (e: java.io.IOException) {
                logger.error("Failed to resolve OIDC metadata on attempt ${attempt + 1}", e)
                lastError = e
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid OIDC metadata configuration on attempt ${attempt + 1}", e)
                lastError = e
            } catch (e: Exception) {
                logger.error("Unexpected error resolving OIDC metadata on attempt ${attempt + 1}", e)
                lastError = e
            }

            if (attempt < 2) {
                kotlinx.coroutines.delay(1000L * (attempt + 1))
            }
        }

        return when (lastError) {
            is ParseException -> MetadataUnavailableError("Failed to parse OIDC metadata: ${lastError.message}").left()
            is java.io.IOException -> NetworkError("Failed to resolve OIDC metadata: ${lastError.message}").left()
            else -> MetadataUnavailableError("Failed to resolve OIDC metadata: ${lastError?.message}").left()
        }
    }

    private suspend fun executeTokenRequest(
        tokenUrl: String,
        metadata: OIDCProviderMetadata,
        includeClientSecret: Boolean,
        parameters: Parameters
    ): Either<OidcFlowError, Pair<TokenResponse, String>> {
        val preferredAuthMethod = resolveTokenAuthMethod(metadata, includeClientSecret)
        val firstAttempt = sendTokenRequest(tokenUrl, parameters, preferredAuthMethod).getOrElse { return it.left() }

        if (
            includeClientSecret &&
            preferredAuthMethod != TokenAuthMethod.NONE &&
            !firstAttempt.first.indicatesSuccess() &&
            isClientAuthError(firstAttempt.second)
        ) {
            logger.warn("Token endpoint rejected configured client authentication method, retrying as public client with PKCE")
            return sendTokenRequest(tokenUrl, parameters, TokenAuthMethod.NONE)
        }

        return firstAttempt.right()
    }

    private suspend fun sendTokenRequest(
        tokenUrl: String,
        parameters: Parameters,
        tokenAuthMethod: TokenAuthMethod
    ): Either<OidcFlowError, Pair<TokenResponse, String>> {
        val oidc = configuration.oidc
        val response = try {
            httpClient.post(tokenUrl) {
                timeout {
                    connectTimeoutMillis = oidc.connectTimeoutMillis
                    socketTimeoutMillis = oidc.socketTimeoutMillis
                }
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters.build {
                        appendAll(parameters)
                        when (tokenAuthMethod) {
                            TokenAuthMethod.NONE -> append("client_id", oidc.clientId)
                            TokenAuthMethod.CLIENT_SECRET_POST -> {
                                append("client_id", oidc.clientId)
                                append("client_secret", oidc.clientSecret)
                            }

                            TokenAuthMethod.CLIENT_SECRET_BASIC -> {
                                val credentials = Base64.getEncoder()
                                    .encodeToString("${oidc.clientId}:${oidc.clientSecret}".toByteArray())
                                headers.append(HttpHeaders.Authorization, "Basic $credentials")
                            }
                        }
                    }.formUrlEncode()
                )
            }
        } catch (e: java.io.IOException) {
            logger.error("Token request network failure", e)
            return NetworkError("Token request network failure").left()
        }

        val body = response.bodyAsText()
        val tokenResponse = try {
            TokenResponse.parse(HTTPResponse(response.status.value).apply {
                setHeader(
                    HttpHeaders.ContentType,
                    response.headers[HttpHeaders.ContentType] ?: ContentType.Application.Json.toString()
                )
                setBody(response.bodyAsText())
            })
        } catch (e: ParseException) {
            logger.error("Failed to parse token response", e)
            return ParseValidationError("Failed to parse token response").left()
        }

        if (!tokenResponse.indicatesSuccess()) {
            logger.warn("Token endpoint response body: {}", body)
        }

        return (tokenResponse to body).right()
    }

    private fun resolveTokenAuthMethod(metadata: OIDCProviderMetadata, includeClientSecret: Boolean): TokenAuthMethod {
        if (!includeClientSecret) return TokenAuthMethod.NONE

        val supportedMethods = metadata.tokenEndpointAuthMethods
        if (supportedMethods == null || supportedMethods.isEmpty()) {
            return TokenAuthMethod.CLIENT_SECRET_BASIC
        }

        return when {
            supportedMethods.contains(ClientAuthenticationMethod.CLIENT_SECRET_POST) -> TokenAuthMethod.CLIENT_SECRET_POST
            supportedMethods.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC) -> TokenAuthMethod.CLIENT_SECRET_BASIC
            supportedMethods.contains(ClientAuthenticationMethod.NONE) -> TokenAuthMethod.NONE
            else -> TokenAuthMethod.CLIENT_SECRET_BASIC
        }
    }

    private fun isClientAuthError(responseBody: String): Boolean {
        return responseBody.contains("invalid_client") || responseBody.contains("unauthorized_client")
    }


    private suspend fun createPushedAuthorizationRequest(
        metadata: OIDCProviderMetadata,
        state: String,
        nonce: String,
        codeChallenge: String
    ): String? {
        val pushedAuthorizationRequestEndpoint = metadata.pushedAuthorizationRequestEndpointURI ?: return null
        val oidc = configuration.oidc

        return try {
            val response = httpClient.post(pushedAuthorizationRequestEndpoint.toString()) {
                timeout {
                    connectTimeoutMillis = oidc.connectTimeoutMillis
                    socketTimeoutMillis = oidc.socketTimeoutMillis
                }
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters.build {
                        append("client_id", oidc.clientId)
                        append("response_type", "code")
                        append("redirect_uri", oidc.redirectUri)
                        append("state", state)
                        append("nonce", nonce)
                        append("scope", oidc.scopes.joinToString(" "))
                        append("code_challenge", codeChallenge)
                        append("code_challenge_method", "S256")
                    }.formUrlEncode()
                )
                if (oidc.clientSecret.isNotBlank()) {
                    val credentials =
                        Base64.getEncoder().encodeToString("${oidc.clientId}:${oidc.clientSecret}".toByteArray())
                    headers.append(HttpHeaders.Authorization, "Basic $credentials")
                }
            }

            if (!response.status.isSuccess()) {
                logger.warn("PAR endpoint returned non-success status: {}", response.status)
                return null
            }

            val body = response.bodyAsText()

            val json = Json.parseToJsonElement(body).jsonObject
            json["request_uri"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.warn("Failed to create PAR request, falling back to regular authorization request", e)
            null
        }
    }

    private fun parseJson(responseBody: String): Either<OidcFlowError, JsonObject> {
        return try {
            Json.parseToJsonElement(responseBody).jsonObject.right()
        } catch (e: Exception) {
            logger.error("Failed to parse token response JSON", e)
            ParseValidationError("Failed to parse token response JSON").left()
        }
    }

    private enum class TokenAuthMethod {
        NONE,
        CLIENT_SECRET_POST,
        CLIENT_SECRET_BASIC
    }
}
