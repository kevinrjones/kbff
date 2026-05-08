package com.knowledgespike.feature.kbff.domain.repository

import arrow.core.Either
import com.knowledgespike.feature.kbff.domain.model.KbffError
import io.ktor.server.sessions.*

interface KbffSessionStorage : SessionStorage {
    override suspend fun read(id: String): String
    override suspend fun write(id: String, value: String)
    override suspend fun invalidate(id: String)

    /**
     * An idiomatic functional read that returns Either
     */
    suspend fun readSession(id: String): Either<KbffError, String>
}
