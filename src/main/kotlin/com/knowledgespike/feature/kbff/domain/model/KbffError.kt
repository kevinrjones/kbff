package com.knowledgespike.feature.kbff.domain.model

import kotlinx.serialization.Serializable

@Serializable
sealed class KbffError(val message: String)

sealed class OidcFlowError(message: String) : KbffError(message)

@Serializable
data class KbffErrorResponse(val error: String, val details: String? = null)

@Serializable
class SessionError(val error: String) : KbffError(error)

@Serializable
class ConfigurationError(val error: String) : KbffError(error)

class NetworkError(val error: String) : OidcFlowError(error)

class ValidationError(val error: String) : OidcFlowError(error)

class MetadataUnavailableError(val error: String) : OidcFlowError(error)

class InvalidTokenResponseError(val error: String) : OidcFlowError(error)

class ParseValidationError(val error: String) : OidcFlowError(error)
