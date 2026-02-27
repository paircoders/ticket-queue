package com.ticketqueue.event.entity

import com.ticketqueue.event.exception.EventException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class EventTest {

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

    @Nested
    @DisplayName("Event.update()")
    inner class Update {

        @Test
        @DisplayName("null이 아닌 필드만 선택적으로 변경된다")
        fun partialUpdate() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply { update(null, null, "기존 설명") }

            event.update(title = "새 제목", artist = null, description = null)

            assertEquals("새 제목", event.title)
            assertEquals("BTS", event.artist)         // 변경 없음
            assertEquals("기존 설명", event.description) // 변경 없음
        }

        @Test
        @DisplayName("description에 빈 문자열을 전달하면 null로 초기화된다")
        fun descriptionClearedByEmptyString() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply { update(null, null, "기존 설명") }

            event.update(title = null, artist = null, description = "")

            assertNull(event.description)
        }

        @Test
        @DisplayName("description에 새 값을 전달하면 해당 값으로 변경된다")
        fun descriptionUpdated() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            event.update(title = null, artist = null, description = "새로운 설명")

            assertEquals("새로운 설명", event.description)
        }

        @Test
        @DisplayName("모든 필드를 동시에 변경할 수 있다")
        fun allFieldsUpdated() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            event.update(title = "수정 제목", artist = "수정 아티스트", description = "수정 설명")

            assertEquals("수정 제목", event.title)
            assertEquals("수정 아티스트", event.artist)
            assertEquals("수정 설명", event.description)
        }
    }

    @Nested
    @DisplayName("Event.softDelete()")
    inner class SoftDelete {

        @Test
        @DisplayName("softDelete 후 deletedAt이 현재 시각에 근사하게 채워진다")
        fun setsDeletedAt() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            assertNull(event.deletedAt)

            val before = LocalDateTime.now()
            event.softDelete()
            val after = LocalDateTime.now()

            assertNotNull(event.deletedAt)
            assertTrue(!event.deletedAt!!.isBefore(before) && !event.deletedAt!!.isAfter(after))
        }

        @Test
        @DisplayName("softDelete를 두 번 호출해도 원본 삭제 시각이 보존된다")
        fun canBeCalledTwice() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            event.softDelete()
            val firstDeletedAt = event.deletedAt!!

            event.softDelete()

            // 두 번째 호출은 무시되어 원본 삭제 시각이 보존됨
            assertEquals(firstDeletedAt, event.deletedAt)
        }
    }

    @Nested
    @DisplayName("Event.changeStatus()")
    inner class ChangeStatus {

        @Test
        @DisplayName("PREPARING → OPEN으로 상태 전이가 된다")
        fun preparingToOpen() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            assertEquals(EventStatus.PREPARING, event.status)
            event.changeStatus(EventStatus.OPEN)
            assertEquals(EventStatus.OPEN, event.status)
        }

        @Test
        @DisplayName("OPEN → ENDED로 상태 전이가 된다")
        fun openToEnded() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply { changeStatus(EventStatus.OPEN) }

            event.changeStatus(EventStatus.ENDED)

            assertEquals(EventStatus.ENDED, event.status)
        }

        @Test
        @DisplayName("OPEN → CANCELLED로 상태 전이가 된다")
        fun openToCancelled() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply { changeStatus(EventStatus.OPEN) }

            event.changeStatus(EventStatus.CANCELLED)

            assertEquals(EventStatus.CANCELLED, event.status)
        }

        @Test
        @DisplayName("ENDED → OPEN 전이 시 INVALID_EVENT_STATUS 예외가 발생한다")
        fun endedToOpenThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply {
                changeStatus(EventStatus.OPEN)
                changeStatus(EventStatus.ENDED)
            }

            assertThrows(EventException::class.java) { event.changeStatus(EventStatus.OPEN) }
        }

        @Test
        @DisplayName("CANCELLED → OPEN 전이 시 예외가 발생한다")
        fun cancelledToOpenThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall).apply { changeStatus(EventStatus.CANCELLED) }

            assertThrows(EventException::class.java) { event.changeStatus(EventStatus.OPEN) }
        }

        @Test
        @DisplayName("PREPARING → ENDED 전이 시 예외가 발생한다 (OPEN 건너뛰기 불가)")
        fun preparingToEndedThrows() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            assertThrows(EventException::class.java) { event.changeStatus(EventStatus.ENDED) }
        }
    }

    @Nested
    @DisplayName("EventSchedule.hasSaleStarted()")
    inner class HasSaleStarted {

        private fun createEventSchedule(saleStartAt: LocalDateTime): EventSchedule {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            return EventSchedule(
                id = UUID.randomUUID(), event = event, playSequence = 1,
                eventStartAt = saleStartAt.plusDays(30),
                eventEndAt = saleStartAt.plusDays(30).plusHours(2),
                saleStartAt = saleStartAt,
                saleEndAt = saleStartAt.plusDays(29),
                createdAt = now, updatedAt = now
            )
        }

        @Test
        @DisplayName("saleStartAt이 현재보다 과거이면 true를 반환한다")
        fun saleAlreadyStarted() {
            val schedule = createEventSchedule(saleStartAt = now.minusHours(1))
            assertTrue(schedule.hasSaleStarted())
        }

        @Test
        @DisplayName("saleStartAt이 현재보다 미래이면 false를 반환한다")
        fun saleNotStartedYet() {
            val schedule = createEventSchedule(saleStartAt = now.plusHours(1))
            assertFalse(schedule.hasSaleStarted())
        }
    }

    @Nested
    @DisplayName("Seat 생성")
    inner class SeatCreation {

        @Test
        @DisplayName("초기 생성 시 status는 AVAILABLE이다")
        fun defaultStatusIsAvailable() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = EventSchedule(
                event = event, playSequence = 1,
                eventStartAt = now.plusDays(30), eventEndAt = now.plusDays(30).plusHours(2),
                saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(29)
            )
            val seat = Seat(
                eventSchedule = schedule,
                seatNumber = "A-1",
                grade = SeatGrade.VIP,
                price = BigDecimal("150000")
            )

            assertEquals(SeatStatus.AVAILABLE, seat.status)
        }
    }
}
