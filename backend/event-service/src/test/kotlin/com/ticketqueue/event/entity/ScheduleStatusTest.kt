package com.ticketqueue.event.entity

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.exception.EventException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class ScheduleStatusTest {

    private val now = LocalDateTime.now()

    private fun createVenue() = Venue(
        id = UUID.randomUUID(), name = "올림픽공원",
        address = "서울시 송파구", city = "서울",
        createdAt = now, updatedAt = now
    )

    private fun createHall(venue: Venue) = Hall(
        id = UUID.randomUUID(), venue = venue,
        name = "KSPO DOME", capacity = 15000,
        seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}""",
        createdAt = now, updatedAt = now
    )

    private fun createEvent(venue: Venue, hall: Hall) = Event(
        id = UUID.randomUUID(), title = "BTS World Tour", artist = "BTS",
        venue = venue, hall = hall, createdAt = now, updatedAt = now
    )

    private fun createSchedule(event: Event, status: ScheduleStatus = ScheduleStatus.UPCOMING) = EventSchedule(
        id = UUID.randomUUID(), event = event, playSequence = 1,
        eventStartAt = now.plusDays(30), eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(29),
        status = status, createdAt = now, updatedAt = now
    )

    @Nested
    @DisplayName("ScheduleStatus.canTransitionTo()")
    inner class CanTransitionTo {

        @Test
        @DisplayName("UPCOMING → ONGOING 전이가 허용된다")
        fun upcomingToOngoing() {
            assertTrue(ScheduleStatus.UPCOMING.canTransitionTo(ScheduleStatus.ONGOING))
        }

        @Test
        @DisplayName("UPCOMING → CANCELLED 전이가 허용된다")
        fun upcomingToCancelled() {
            assertTrue(ScheduleStatus.UPCOMING.canTransitionTo(ScheduleStatus.CANCELLED))
        }

        @Test
        @DisplayName("ONGOING → ENDED 전이가 허용된다")
        fun ongoingToEnded() {
            assertTrue(ScheduleStatus.ONGOING.canTransitionTo(ScheduleStatus.ENDED))
        }

        @Test
        @DisplayName("ONGOING → CANCELLED 전이가 허용된다")
        fun ongoingToCancelled() {
            assertTrue(ScheduleStatus.ONGOING.canTransitionTo(ScheduleStatus.CANCELLED))
        }

        @Test
        @DisplayName("UPCOMING → ENDED 전이는 허용되지 않는다")
        fun upcomingToEndedDenied() {
            assertFalse(ScheduleStatus.UPCOMING.canTransitionTo(ScheduleStatus.ENDED))
        }

        @Test
        @DisplayName("ENDED → UPCOMING 전이는 허용되지 않는다 (terminal)")
        fun endedToUpcomingDenied() {
            assertFalse(ScheduleStatus.ENDED.canTransitionTo(ScheduleStatus.UPCOMING))
        }

        @Test
        @DisplayName("ENDED → ONGOING 전이는 허용되지 않는다 (terminal)")
        fun endedToOngoingDenied() {
            assertFalse(ScheduleStatus.ENDED.canTransitionTo(ScheduleStatus.ONGOING))
        }

        @Test
        @DisplayName("ENDED → CANCELLED 전이는 허용되지 않는다 (terminal)")
        fun endedToCancelledDenied() {
            assertFalse(ScheduleStatus.ENDED.canTransitionTo(ScheduleStatus.CANCELLED))
        }

        @Test
        @DisplayName("CANCELLED → UPCOMING 전이는 허용되지 않는다 (terminal)")
        fun cancelledToUpcomingDenied() {
            assertFalse(ScheduleStatus.CANCELLED.canTransitionTo(ScheduleStatus.UPCOMING))
        }

        @Test
        @DisplayName("CANCELLED → ONGOING 전이는 허용되지 않는다 (terminal)")
        fun cancelledToOngoingDenied() {
            assertFalse(ScheduleStatus.CANCELLED.canTransitionTo(ScheduleStatus.ONGOING))
        }

        @Test
        @DisplayName("CANCELLED → ENDED 전이는 허용되지 않는다 (terminal)")
        fun cancelledToEndedDenied() {
            assertFalse(ScheduleStatus.CANCELLED.canTransitionTo(ScheduleStatus.ENDED))
        }

        @Test
        @DisplayName("ONGOING → UPCOMING 전이는 허용되지 않는다")
        fun ongoingToUpcomingDenied() {
            assertFalse(ScheduleStatus.ONGOING.canTransitionTo(ScheduleStatus.UPCOMING))
        }
    }

    @Nested
    @DisplayName("EventSchedule.changeStatus()")
    inner class ChangeStatus {

        @Test
        @DisplayName("UPCOMING → ONGOING으로 상태 전이가 성공한다")
        fun upcomingToOngoing() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.UPCOMING)

            schedule.changeStatus(ScheduleStatus.ONGOING)

            assertEquals(ScheduleStatus.ONGOING, schedule.status)
        }

        @Test
        @DisplayName("UPCOMING → CANCELLED로 상태 전이가 성공한다")
        fun upcomingToCancelled() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.UPCOMING)

            schedule.changeStatus(ScheduleStatus.CANCELLED)

            assertEquals(ScheduleStatus.CANCELLED, schedule.status)
        }

        @Test
        @DisplayName("ONGOING → ENDED로 상태 전이가 성공한다")
        fun ongoingToEnded() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.ONGOING)

            schedule.changeStatus(ScheduleStatus.ENDED)

            assertEquals(ScheduleStatus.ENDED, schedule.status)
        }

        @Test
        @DisplayName("ONGOING → CANCELLED로 상태 전이가 성공한다")
        fun ongoingToCancelled() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.ONGOING)

            schedule.changeStatus(ScheduleStatus.CANCELLED)

            assertEquals(ScheduleStatus.CANCELLED, schedule.status)
        }

        @Test
        @DisplayName("ENDED → UPCOMING 전이 시 INVALID_SCHEDULE_STATUS 예외가 발생한다")
        fun endedToUpcomingThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.ENDED)

            val ex = assertThrows(EventException::class.java) {
                schedule.changeStatus(ScheduleStatus.UPCOMING)
            }
            assertEquals(ErrorCode.INVALID_SCHEDULE_STATUS, ex.errorCode)
        }

        @Test
        @DisplayName("CANCELLED → ONGOING 전이 시 INVALID_SCHEDULE_STATUS 예외가 발생한다")
        fun cancelledToOngoingThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.CANCELLED)

            val ex = assertThrows(EventException::class.java) {
                schedule.changeStatus(ScheduleStatus.ONGOING)
            }
            assertEquals(ErrorCode.INVALID_SCHEDULE_STATUS, ex.errorCode)
        }

        @Test
        @DisplayName("UPCOMING → ENDED 전이 시 예외가 발생한다 (ONGOING 건너뛰기 불가)")
        fun upcomingToEndedThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.UPCOMING)

            val ex = assertThrows(EventException::class.java) {
                schedule.changeStatus(ScheduleStatus.ENDED)
            }
            assertEquals(ErrorCode.INVALID_SCHEDULE_STATUS, ex.errorCode)
        }
    }
}
