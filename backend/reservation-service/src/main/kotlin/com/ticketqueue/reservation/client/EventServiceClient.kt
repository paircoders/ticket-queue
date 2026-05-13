package com.ticketqueue.reservation.client

import com.ticketqueue.common.config.InternalFeignConfig
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.util.UUID

@FeignClient(
    name = "event-service",
    url = "\${spring.cloud.openfeign.client.config.event-service.url}",
    fallbackFactory = EventServiceClientFallbackFactory::class,
    configuration = [InternalFeignConfig::class]
)
interface EventServiceClient {

    @GetMapping("/internal/seats/status/{scheduleId}")
    fun getSoldSeats(@PathVariable scheduleId: UUID): SoldSeatsResponse

    @GetMapping("/internal/seats/{scheduleId}/details")
    fun getSeatDetails(
        @PathVariable scheduleId: UUID,
        @RequestParam seatIds: List<UUID>
    ): SeatDetailsResponse

    data class SoldSeatsResponse(
        val scheduleId: UUID,
        val soldSeatIds: List<UUID>,
        val totalSeats: Long
    )

    data class SeatDetailsResponse(
        val scheduleId: UUID,
        val eventId: UUID,
        val seats: List<SeatDetail>
    ) {
        data class SeatDetail(
            val seatId: UUID,
            val seatNumber: String,
            val grade: String,
            val price: BigDecimal
        )
    }
}
