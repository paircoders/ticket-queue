package com.ticketqueue.reservation.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@FeignClient(name = "event-service", url = "\${feign.client.config.event-service.url}")
interface EventServiceClient {

    @GetMapping("/internal/seats/status/{scheduleId}")
    fun getSoldSeats(@PathVariable scheduleId: UUID): SoldSeatsResponse

    data class SoldSeatsResponse(
        val scheduleId: UUID,
        val soldSeatIds: List<UUID>
    )
}
