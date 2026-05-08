package com.knowledgespike.feature.kbff.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.knowledgespike.feature.kbff.domain.model.KbffError
import com.knowledgespike.feature.kbff.domain.model.SessionError
import com.knowledgespike.feature.kbff.domain.repository.KbffSessionStorage
import java.util.concurrent.ConcurrentHashMap

class InMemoryKbffSessionStorage : KbffSessionStorage {
    private val sessions = ConcurrentHashMap<String, String>()

    override suspend fun invalidate(id: String) {
        sessions.remove(id)
    }

    override suspend fun read(id: String): String {
        return readSession(id).fold(
            ifLeft = { throw NoSuchElementException(it.message) },
            ifRight = { it }
        )
    }

    override suspend fun readSession(id: String): Either<KbffError, String> {
        return sessions[id]?.right() ?: SessionError("Session $id not found").left()
    }

    override suspend fun write(id: String, value: String) {
        sessions[id] = value
    }
}
