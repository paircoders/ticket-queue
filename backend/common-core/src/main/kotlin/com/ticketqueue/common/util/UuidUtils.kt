package com.ticketqueue.common.util

import java.util.UUID

object UuidUtils {
    fun generate(): UUID = UUID.randomUUID()

    fun fromString(value: String): UUID = UUID.fromString(value)

    fun isValid(value: String): Boolean {
        return try {
            UUID.fromString(value)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
