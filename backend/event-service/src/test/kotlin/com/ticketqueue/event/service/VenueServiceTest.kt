package com.ticketqueue.event.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.VenueDto
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Venue
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.VenueRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class VenueServiceTest {

    private lateinit var venueRepository: VenueRepository
    private lateinit var hallRepository: HallRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var venueService: VenueService

    private val venueId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun createVenue(
        id: UUID = venueId,
        name: String = "올림픽공원",
        address: String = "서울시 송파구",
        city: String = "서울"
    ): Venue = Venue(
        id = id,
        name = name,
        address = address,
        city = city,
        createdAt = now,
        updatedAt = now
    )

    @BeforeEach
    fun setUp() {
        venueRepository = mockk()
        hallRepository = mockk()
        eventRepository = mockk()
        venueService = VenueService(venueRepository, hallRepository, eventRepository)
    }

    @Nested
    @DisplayName("createVenue")
    inner class CreateVenue {

        @Test
        @DisplayName("성공적으로 공연장을 생성한다")
        fun success() {
            val request = VenueDto.CreateRequest(
                name = "올림픽공원",
                address = "서울시 송파구",
                city = "서울"
            )
            val venue = createVenue()
            every { venueRepository.save(any()) } returns venue

            val result = venueService.createVenue(request)

            assertEquals(venueId, result.id)
            assertEquals("올림픽공원", result.name)
            assertEquals("서울시 송파구", result.address)
            assertEquals("서울", result.city)
            verify { venueRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("getVenues")
    inner class GetVenues {

        @Test
        @DisplayName("필터 없이 공연장 목록을 조회한다")
        fun withoutFilter() {
            val venues = listOf(createVenue())
            val pageable = PageRequest.of(0, 20)
            every { venueRepository.findAll(pageable) } returns PageImpl(venues, pageable, 1)

            val result = venueService.getVenues(0, 20, null)

            assertEquals(1, result.totalElements)
            assertEquals("올림픽공원", result.content[0].name)
        }

        @Test
        @DisplayName("도시별 공연장 목록을 조회한다")
        fun withCityFilter() {
            val venues = listOf(createVenue())
            val pageable = PageRequest.of(0, 20)
            every { venueRepository.findByCity("서울", pageable) } returns PageImpl(venues, pageable, 1)

            val result = venueService.getVenues(0, 20, "서울")

            assertEquals(1, result.totalElements)
            assertEquals("서울", result.content[0].city)
        }
    }

    @Nested
    @DisplayName("getVenue")
    inner class GetVenue {

        @Test
        @DisplayName("공연장 상세를 조회한다")
        fun success() {
            val venue = createVenue()
            val halls = listOf(
                Hall(
                    id = UUID.randomUUID(),
                    venue = venue,
                    name = "KSPO DOME",
                    capacity = 15000,
                    seatTemplate = "{}",
                    createdAt = now,
                    updatedAt = now
                )
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findByVenueId(venueId) } returns halls

            val result = venueService.getVenue(venueId)

            assertEquals(venueId, result.id)
            assertEquals(1, result.halls.size)
            assertEquals("KSPO DOME", result.halls[0].name)
        }

        @Test
        @DisplayName("존재하지 않는 공연장 조회 시 예외가 발생한다")
        fun notFound() {
            every { venueRepository.findById(venueId) } returns Optional.empty()

            val exception = assertThrows<EventException> {
                venueService.getVenue(venueId)
            }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("updateVenue")
    inner class UpdateVenue {

        @Test
        @DisplayName("전체 필드를 업데이트한다")
        fun fullUpdate() {
            val venue = createVenue()
            val request = VenueDto.UpdateRequest(
                name = "고척스카이돔",
                address = "서울시 구로구",
                city = "서울"
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)

            val result = venueService.updateVenue(venueId, request)

            assertEquals("고척스카이돔", result.name)
            assertEquals("서울시 구로구", result.address)
        }

        @Test
        @DisplayName("부분 필드만 업데이트한다")
        fun partialUpdate() {
            val venue = createVenue()
            val request = VenueDto.UpdateRequest(name = "고척스카이돔")
            every { venueRepository.findById(venueId) } returns Optional.of(venue)

            val result = venueService.updateVenue(venueId, request)

            assertEquals("고척스카이돔", result.name)
            assertEquals("서울시 송파구", result.address)
            assertEquals("서울", result.city)
        }

        @Test
        @DisplayName("존재하지 않는 공연장 업데이트 시 예외가 발생한다")
        fun notFound() {
            val request = VenueDto.UpdateRequest(name = "고척스카이돔")
            every { venueRepository.findById(venueId) } returns Optional.empty()

            val exception = assertThrows<EventException> {
                venueService.updateVenue(venueId, request)
            }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("deleteVenue")
    inner class DeleteVenue {

        @Test
        @DisplayName("성공적으로 공연장을 삭제한다")
        fun success() {
            val venue = createVenue()
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { eventRepository.existsByVenueId(venueId) } returns false
            every { hallRepository.existsByVenueId(venueId) } returns false
            every { venueRepository.delete(venue) } returns Unit

            val result = venueService.deleteVenue(venueId)

            assertNotNull(result.message)
            verify { venueRepository.delete(venue) }
        }

        @Test
        @DisplayName("존재하지 않는 공연장 삭제 시 예외가 발생한다")
        fun notFound() {
            every { venueRepository.findById(venueId) } returns Optional.empty()

            val exception = assertThrows<EventException> {
                venueService.deleteVenue(venueId)
            }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("공연이 존재하는 공연장 삭제 시 예외가 발생한다")
        fun hasEvents() {
            val venue = createVenue()
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { eventRepository.existsByVenueId(venueId) } returns true

            val exception = assertThrows<EventException> {
                venueService.deleteVenue(venueId)
            }
            assertEquals(ErrorCode.VENUE_HAS_EVENTS, exception.errorCode)
        }

        @Test
        @DisplayName("홀이 존재하는 공연장 삭제 시 예외가 발생한다")
        fun hasHalls() {
            val venue = createVenue()
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { eventRepository.existsByVenueId(venueId) } returns false
            every { hallRepository.existsByVenueId(venueId) } returns true

            val exception = assertThrows<EventException> {
                venueService.deleteVenue(venueId)
            }
            assertEquals(ErrorCode.VENUE_HAS_HALLS, exception.errorCode)
        }
    }
}
