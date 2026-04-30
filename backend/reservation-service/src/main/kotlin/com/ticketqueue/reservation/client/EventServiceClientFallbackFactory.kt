package com.ticketqueue.reservation.client

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.exception.ReservationException
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EventServiceClientFallbackFactory : FallbackFactory<EventServiceClient> {
    override fun create(cause: Throwable): EventServiceClient = object : EventServiceClient {
        override fun getSoldSeats(scheduleId: UUID): EventServiceClient.SoldSeatsResponse =
            throw mapToReservationException(cause)

        override fun getSeatDetails(
            scheduleId: UUID,
            seatIds: List<UUID>
        ): EventServiceClient.SeatDetailsResponse =
            throw mapToReservationException(cause)
    }

    private fun mapToReservationException(cause: Throwable): ReservationException = when {
        cause is BusinessException && cause.errorCode == ErrorCode.RESOURCE_NOT_FOUND ->
            ReservationException(ErrorCode.SCHEDULE_NOT_FOUND, cause.message ?: ErrorCode.SCHEDULE_NOT_FOUND.message, cause)
        cause is BusinessException ->
            ReservationException(cause.errorCode, cause.message ?: cause.errorCode.message, cause)
        else ->
            ReservationException(ErrorCode.INTERNAL_SERVER_ERROR, "Event Service 응답 실패", cause)
    }
}
