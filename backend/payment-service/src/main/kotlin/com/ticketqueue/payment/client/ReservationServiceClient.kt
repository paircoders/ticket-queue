package com.ticketqueue.payment.client

import com.ticketqueue.common.config.InternalFeignConfig
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@FeignClient(
    name = "reservation-service",
    url = "\${spring.cloud.openfeign.client.config.reservation-service.url}",
    configuration = [InternalFeignConfig::class]
)
interface ReservationServiceClient {

    @GetMapping("/internal/reservations/{reservationId}")
    fun getReservation(@PathVariable reservationId: UUID): ReservationDetailResponse

    enum class ReservationStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }

    data class ReservationDetailResponse(
        val reservationId: UUID,
        val userId: UUID,
        val scheduleId: UUID,
        val totalAmount: BigDecimal,
        val status: ReservationStatus,
        val holdExpiresAt: LocalDateTime,
        val seatIds: List<UUID>
    )
}
