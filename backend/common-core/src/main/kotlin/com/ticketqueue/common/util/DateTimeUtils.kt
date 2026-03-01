package com.ticketqueue.common.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateTimeUtils {
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    val UTC: ZoneId = ZoneId.of("UTC")
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    fun now(): LocalDateTime = LocalDateTime.now(UTC)
    fun format(dateTime: LocalDateTime): String = dateTime.format(FORMATTER)
    fun parse(dateTimeString: String): LocalDateTime = LocalDateTime.parse(dateTimeString, FORMATTER)
    fun nowFormatted(): String = format(now())
}
