package com.ticketqueue.reservation.client

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.exception.ReservationException
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EventServiceClientFallbackFactory : FallbackFactory<EventServiceClient> {
    override fun create(cause: Throwable): EventServiceClient = object : EventServiceClient {
        override fun getSoldSeats(scheduleId: UUID): EventServiceClient.SoldSeatsResponse =
            throw ReservationException(ErrorCode.INTERNAL_SERVER_ERROR, "Event Service 응답 실패", cause)

        override fun getSeatDetails(
            scheduleId: UUID,
            seatIds: List<UUID>
        ): EventServiceClient.SeatDetailsResponse =
            throw ReservationException(ErrorCode.INTERNAL_SERVER_ERROR, "Event Service 응답 실패", cause)
    }
}
