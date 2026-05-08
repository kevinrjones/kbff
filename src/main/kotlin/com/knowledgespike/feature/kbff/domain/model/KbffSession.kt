package com.knowledgespike.feature.kbff.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class KbffClaim(
    val type: String,
    val value: String,
    val valueType: String? = null
)

@Serializable
data class KbffSession(
    val sessionId: String,
    val accessToken: String? = null,
    val idToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val codeVerifier: String? = null,
    val state: String? = null,
    val nonce: String? = null,
    val returnUrl: String? = null,
    val claims: List<KbffClaim> = emptyList(),
    val userInfoClaims: List<KbffClaim> = emptyList()
)
