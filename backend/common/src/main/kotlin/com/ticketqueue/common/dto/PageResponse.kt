package com.ticketqueue.common.dto

data class PageResponse<T>(
    val list: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long
)