package com.vaultnote.core.common

import java.util.UUID

fun interface IdGenerator {
    fun newId(): String
}

object UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
