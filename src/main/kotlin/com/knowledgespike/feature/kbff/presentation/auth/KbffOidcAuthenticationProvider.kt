package com.knowledgespike.feature.kbff.presentation.auth

import com.knowledgespike.feature.kbff.domain.model.KbffClaim
import com.knowledgespike.feature.kbff.domain.model.KbffSession
import com.knowledgespike.feature.kbff.presentation.route.normalizeReturnUrl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import java.net.URI

/**
 * Represents an authenticated user principal within the KBFF OIDC authentication system.
 *
 * This class is used to encapsulate the details of an authenticated user session, including
 * session-specific values, user information, and authentication tokens, and is typically accessed
 * through the `ApplicationCall.principal` method when handling authenticated requests.
 *
 * @property sessionId A unique identifier for the user's session.
 * @property name The name of the authenticated user.
 * @property claims A map of claims that provide additional information about the user, such as roles or permissions.
 * @property accessToken The access token issued to the user; used for authorizing requests to secured resources. Nullable.
 * @property idToken The ID token issued to the user; contains user identity information in JWT format. Nullable.
 */
data class KbffUserPrincipal(
    val sessionId: String,
    val name: String,
    val claims: List<KbffClaim>,
    val accessToken: String?,
    val idToken: String?
)

/**
 * Provides authentication support for OIDC-based applications in a BFF (Backend for Frontend) architecture.
 * This class is designed to be used as part of Ktor's authentication system and integrates with session-based
 * validation and redirection to external authentication providers.
 *
 * @constructor
 * Private constructor that accepts a configuration object to initialize the provider.
 *
 * @param config Configuration object used to customize the authentication provider behavior.
 */
class KbffOidcAuthenticationProvider internal constructor(config: Config) : AuthenticationProvider(config) {

    /**
     * A function reference used within the authentication flow to handle challenge responses.
     *
     * This function is invoked when authentication challenges occur, enabling custom behavior,
     * such as redirecting the user or prompting for additional input, during scenarios where
     * no credentials are provided or where authentication fails.
     *
     * It operates in conjunction with the `onAuthenticate` method, handling cases of
     * `AuthenticationFailedCause.NoCredentials` and `AuthenticationFailedCause.InvalidCredentials`.
     *
     * The provided function is configured externally through the application's settings and
     * retrieved from the `KbffConfiguration` object.
     */
    private val challengeFunction = config.challengeFunction
    /**
     * A validation function that processes the authentication logic for a given application call and session.
     *
     * It is invoked within the `onAuthenticate` method to determine if the session is valid and retrieve the
     * associated principal. If the validation fails, appropriate authentication challenges are triggered.
     *
     * The implementation of this function is provided by the `config.validate` property.
     */
    private val validate = config.validate

    /**
     * Configuration class for the `KbffOidcAuthenticationProvider`.
     *
     * This class provides properties and methods to configure the behavior of the
     * authentication provider, including login paths, callback paths, validation,
     * and challenge handling. It is internally created and extended from the
     * `AuthenticationProvider.Config`.
     *
     * @constructor
     * Creates a new instance of the `Config` class with the specified `name`.
     * This is used to initialize configuration parameters for the authentication provider.
     *
     * @param name The name of the authentication provider.
     */
    class Config internal constructor(name: String?) : AuthenticationProvider.Config(name) {
        /**
         * Specifies the path used for login redirection.
         *
         * This property represents the endpoint URL where the application redirects users
         * when they need to log in. The value can be updated dynamically based on configuration
         * or custom requirements.
         *
         * By default, the login path is set to "/bff/login".
         *
         * Usage examples include resolving the login URL during request processing
         * or configuring the redirection behavior using methods like `redirectToLogin`.
         */
        internal var loginPath: String = "/bff/login"
        /**
         * Defines the callback path used for handling authentication responses.
         *
         * This variable is typically configured to specify the endpoint where
         * identity providers redirect the user after authentication. The server
         * processes the response at this path to complete the login process.
         *
         * By default, the callback path is set to "/signin-oidc".
         */
        internal var callbackPath: String = "/signin-oidc"
        /**
         * Specifies the name of the query parameter used to pass the return URL in authentication flows.
         *
         * This variable is utilized to identify and extract the return URL from requests during processes
         * such as login redirection. The return URL typically indicates the destination where the user
         * should be redirected after successfully completing the authentication process.
         *
         * The default value for this parameter is "returnUrl", but it can be customized to align with
         * specific application requirements by invoking the `redirectToLogin` method.
         */
        internal var returnUrlParameterName: String = "returnUrl"
        /**
         * A suspendable function used to handle authentication challenges for incoming application calls.
         * This implementation constructs a redirection URL to the login path, ensuring the current request
         * URL is properly normalized and appended as a return parameter. It then redirects the user to this
         * constructed URL to initiate the authentication process.
         *
         * The method utilizes helper functions to:
         * 1. Construct the current request's full URL (`buildCurrentRequestUrl`).
         * 2. Normalize the return URL to avoid conflicts with the callback path (`normalizeReturnUrl`).
         * 3. Resolve the appropriate login path for the redirection (`resolveLoginPath`).
         *
         * The generated URL includes a return URL parameter to facilitate post-login redirection back to the original request.
         *
         * Note: This variable can be customized using the `challenge` method.
         */
        internal var challengeFunction: suspend ApplicationCall.() -> Unit = {
            val returnUrl = buildCurrentRequestUrl()
            val safeReturnUrl = normalizeReturnUrl(
                returnUrl = returnUrl,
                host = request.host(),
                port = request.port(),
                callbackPath = callbackPath,
                scheme = request.origin.scheme
            )
            val redirectPath = URLBuilder(resolveLoginPath()).apply {
                parameters.append(returnUrlParameterName, safeReturnUrl)
            }.buildString()
            respondRedirect(redirectPath)
        }

        /**
         * A suspendable lambda function used to validate and transform a given [KbffSession] into a [KbffUserPrincipal].
         * The primary purpose is to authenticate a session by verifying its access token and extracting user-specific
         * details to create a [KbffUserPrincipal]. If the session's access token is null or blank, the validation fails,
         * and the function returns null.
         *
         * The validation process involves:
         * - Extracting a user's display name from the session's claims. If no specific claim is found, a default value is used.
         * - Creating a [KbffUserPrincipal] that encapsulates user-related data such as session ID, name, claims, and tokens.
         *
         * This property is configurable via the `validate` function in the containing class, allowing custom validation logic
         * to be provided as needed for different authentication scenarios.
         */
        internal var validate: suspend ApplicationCall.(KbffSession) -> KbffUserPrincipal? = { session ->
            if (session.accessToken.isNullOrBlank()) {
                null
            } else {
                val mergedClaims = (session.claims + session.userInfoClaims).distinctBy { it.type to it.value }
                val displayName = mergedClaims.find { it.type == "name" }?.value
                    ?: mergedClaims.find { it.type == "preferred_username" }?.value
                    ?: mergedClaims.find { it.type == "sub" }?.value
                    ?: "Unknown"
                KbffUserPrincipal(
                    sessionId = session.sessionId,
                    name = displayName,
                    claims = mergedClaims,
                    accessToken = session.accessToken,
                    idToken = session.idToken
                )
            }
        }

        /**
         * Configures the validation mechanism for the authentication process.
         * This method allows you to define a custom validation logic to authenticate
         * a user based on the provided session information.
         *
         * @param block A suspendable block that takes an [ApplicationCall] and a [KbffSession] as parameters.
         *              The block should return a [KbffUserPrincipal] if the validation is successful, or null otherwise.
         */
        fun validate(block: suspend ApplicationCall.(KbffSession) -> KbffUserPrincipal?) {
            validate = block
        }

        /**
         * Configures a custom challenge function to handle authentication challenges during user interaction.
         * The provided function will be invoked as part of the authentication process to determine how
         * to respond when a challenge is needed (e.g., redirecting to a login page or rendering an error).
         *
         * @param block A suspend function that defines the logic to execute when an authentication challenge occurs.
         *              The function operates in the context of an [ApplicationCall].
         */
        fun challenge(block: suspend ApplicationCall.() -> Unit) {
            challengeFunction = block
        }

        /**
         * Configures the redirection behavior for login requests within the application.
         *
         * @param loginPath The endpoint that handles login requests. Defaults to "/bff/login".
         * @param callbackPath The endpoint where the authentication provider will send the response after login. Defaults to "/signin-oidc".
         * @param returnUrlParameterName The query parameter name used to specify the original URL the user was trying to access. Defaults to "returnUrl".
         */
        fun redirectToLogin(
            loginPath: String = "/bff/login",
            callbackPath: String = "/signin-oidc",
            returnUrlParameterName: String = "returnUrl"
        ) {
            this.loginPath = loginPath
            this.callbackPath = callbackPath
            this.returnUrlParameterName = returnUrlParameterName
        }

        /**
         * Resolves the login path for the current application's request, taking into account
         * whether the provided login path is absolute or relative. If the login path is relative,
         * it is expanded with the current request's scheme, host, and port. For absolute paths,
         * additional checks ensure compatibility with the request's scheme, host, and port.
         *
         * @return The fully resolved login path as a string. If the login path is invalid or cannot
         *         be parsed, the original `loginPath` value is returned.
         */
        private fun ApplicationCall.resolveLoginPath(): String {
            val localScheme = request.origin.scheme
            val localHost = request.host()
            val localPort = request.port()

            val parsed = runCatching { URI(loginPath) }.getOrNull() ?: return loginPath

            if (parsed.isAbsolute) {
                if (parsed.host == null || parsed.port != -1) {
                    return loginPath
                }

                val requestUsesDefaultPort = isDefaultPort(localScheme, localPort)
                val sameHost = parsed.host.equals(localHost, ignoreCase = true)
                val sameScheme = parsed.scheme.equals(localScheme, ignoreCase = true)

                if (!sameHost || !sameScheme || requestUsesDefaultPort) {
                    return loginPath
                }

                return URLBuilder(loginPath).apply {
                    port = localPort
                }.buildString()
            } else {
                // For relative paths, prepend the current request's scheme, host, and port
                return URLBuilder().apply {
                    protocol = URLProtocol.createOrDefault(localScheme)
                    host = localHost
                    port = localPort
                    encodedPath = loginPath
                }.buildString()
            }
        }

        /**
         * Constructs a relative URL for the current request, preserving path and query.
         *
         * @return The relative URL of the current request.
         */
        private fun ApplicationCall.buildCurrentRequestUrl(): String {
            return request.uri
        }

        /**
         * Parses a host header string to extract the host and port.
         * The method attempts to parse the host header using URI parsing rules.
         * If parsing fails, it attempts a fallback by manually splitting the host and port.
         *
         * @param hostHeader The host header string to parse. Can be null or blank.
         * @return A pair containing the host and port. The host is returned as a string and the port as an integer.
         *         If the port is not specified, it returns null for the port.
         *         If the host header is invalid or blank, it returns a pair of nulls.
         */
        private fun parseHostAndPort(hostHeader: String?): Pair<String?, Int?> {
            if (hostHeader.isNullOrBlank()) {
                return null to null
            }

            return runCatching {
                val uri = URI("http://$hostHeader")
                uri.host to if (uri.port == -1) null else uri.port
            }.getOrElse {
                hostHeader.substringBefore(':').takeIf { it.isNotBlank() } to
                    hostHeader.substringAfter(':', "").toIntOrNull()
            }
        }

        /**
         * Determines whether the given port is the default port for the specified scheme.
         *
         * @param scheme The scheme (e.g., "http" or "https") to compare.
         * @param port The port number to validate against the default for the given scheme.
         * @return `true` if the port is the default for the specified scheme, otherwise `false`.
         */
        private fun isDefaultPort(scheme: String, port: Int): Boolean =
            (scheme.equals("http", ignoreCase = true) && port == 80) ||
                (scheme.equals("https", ignoreCase = true) && port == 443)
    }

    /**
     * Handles the authentication process using the provided [AuthenticationContext].
     *
     * The method determines the authentication status based on the session information and validates
     * the credentials. If the session is missing or the validation fails, it triggers a challenge
     * to the client. On successful authentication, the principal associated with the session is set
     * in the context.
     *
     * @param context The [AuthenticationContext] containing call and session information used during authentication.
     */
    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val session = context.call.sessions.get<KbffSession>()
        if (session == null) {
            context.challenge("kbff-oidc", AuthenticationFailedCause.NoCredentials) { challenge, _ ->
                challengeFunction(context.call)
                challenge.complete()
            }
            return
        }

        val principal = validate(context.call, session)
        if (principal == null) {
            context.challenge("kbff-oidc", AuthenticationFailedCause.InvalidCredentials) { challenge, _ ->
                challengeFunction(context.call)
                challenge.complete()
            }
            return
        }

        context.principal(principal)
    }
}

/**
 * Registers a custom OIDC (OpenID Connect) authentication provider within the current
 * [AuthenticationConfig]. This integration facilitates the implementation of an authentication
 * flow consistent with the Keycloak BFF (Backend For Frontend) pattern.
 *
 * @param name An optional name to uniquely identify the authentication provider instance.
 *             Defaults to null, in which case a default name is assigned.
 * @param configure A configuration block to customize the behavior and settings of the
 *                  authentication provider. Use this block to specify validation logic,
 *                  challenge behavior, or other customizations.
 */
fun AuthenticationConfig.kbffOidc(
    name: String? = null,
    configure: KbffOidcAuthenticationProvider.Config.() -> Unit = {}
) {
    val provider = KbffOidcAuthenticationProvider.Config(name).apply(configure)
    register(KbffOidcAuthenticationProvider(provider))
}