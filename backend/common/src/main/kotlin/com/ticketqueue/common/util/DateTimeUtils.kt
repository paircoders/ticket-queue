package com.ticketqueue.common.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DateTimeUtils {
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun format(dateTime: LocalDateTime): String = dateTime.format(FORMATTER)

    fun parse(dateTimeString: String): LocalDateTime = LocalDateTime.parse(dateTimeString, FORMATTER)

    fun now(): String = format(LocalDateTime.now())
}
