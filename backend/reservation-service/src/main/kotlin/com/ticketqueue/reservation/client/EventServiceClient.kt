package com.ticketqueue.reservation.client

import com.ticketqueue.common.config.InternalFeignConfig
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.LocalDateTime
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

    @GetMapping("/internal/schedules/{scheduleId}/info")
    fun getScheduleInfo(@PathVariable scheduleId: UUID): ScheduleInfoResponse

    @GetMapping("/internal/events/{eventId}/info")
    fun getEventInfo(@PathVariable eventId: UUID): EventInfoResponse

    @GetMapping("/internal/events/batch")
    fun getEventInfoBatch(@RequestParam eventIds: List<UUID>): EventInfoBatchResponse

    data class EventInfoResponse(
        val eventId: UUID,
        val title: String,
        val artist: String,
        val venueName: String,
        val hallName: String
    )

    data class EventInfoBatchResponse(
        val events: List<EventInfoResponse>
    )

    data class ScheduleInfoResponse(
        val scheduleId: UUID,
        val eventId: UUID,
        val eventStartAt: LocalDateTime,
        val eventEndAt: LocalDateTime,
        val saleStartAt: LocalDateTime,
        val saleEndAt: LocalDateTime
    )

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
