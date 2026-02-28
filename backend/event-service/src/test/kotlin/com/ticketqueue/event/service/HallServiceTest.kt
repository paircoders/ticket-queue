package com.ticketqueue.event.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.dto.SeatTemplateDto
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
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class HallServiceTest {

    private lateinit var hallRepository: HallRepository
    private lateinit var venueRepository: VenueRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var redisTemplate: RedisTemplate<String, Any>
    private lateinit var valueOps: ValueOperations<String, Any>
    private lateinit var hallService: HallService

    private val cacheProperties = CacheProperties()
    private val venueId = UUID.randomUUID()
    private val hallId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private val seatTemplateDto = SeatTemplateDto(
        rows = listOf("A", "B", "C"),
        seatsPerRow = 10,
        gradeMapping = mapOf("A" to "VIP", "B" to "S", "C" to "A")
    )

    private fun createVenue(): Venue = Venue(
        id = venueId,
        name = "올림픽공원",
        address = "서울시 송파구",
        city = "서울",
        createdAt = now,
        updatedAt = now
    )

    private fun createHall(
        id: UUID = hallId,
        venue: Venue = createVenue(),
        name: String = "KSPO DOME",
        capacity: Int = 15000,
        seatTemplate: String? = null
    ): Hall = Hall(
        id = id,
        venue = venue,
        name = name,
        capacity = capacity,
        seatTemplate = seatTemplate ?: objectMapper.writeValueAsString(seatTemplateDto),
        createdAt = now,
        updatedAt = now
    )

    @BeforeEach
    fun setUp() {
        hallRepository = mockk()
        venueRepository = mockk()
        eventRepository = mockk()
        objectMapper = jacksonObjectMapper().apply { registerModule(JavaTimeModule()) }
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any<String>()) } returns null
        every { redisTemplate.delete(any<String>()) } returns true

        hallService = HallService(
            hallRepository, venueRepository, eventRepository,
            objectMapper, redisTemplate, cacheProperties
        )
    }

    @Nested
    @DisplayName("createHall")
    inner class CreateHall {

        @Test
        @DisplayName("성공적으로 홀을 생성한다")
        fun success() {
            val venue = createVenue()
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.existsByVenueIdAndName(venueId, "KSPO DOME") } returns false
            every { hallRepository.save(any()) } returns createHall(venue = venue)

            val result = hallService.createHall(venueId, request)

            assertEquals(hallId, result.id)
            assertEquals("KSPO DOME", result.name)
            assertEquals(15000, result.capacity)
            verify { hallRepository.save(any()) }
        }

        @Test
        @DisplayName("존재하지 않는 공연장의 홀 생성 시 예외가 발생한다")
        fun venueNotFound() {
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto
            )
            every { venueRepository.findById(venueId) } returns Optional.empty()

            val exception = assertThrows<EventException> {
                hallService.createHall(venueId, request)
            }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("동일 공연장 내 중복 이름 시 예외가 발생한다")
        fun duplicateName() {
            val venue = createVenue()
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.existsByVenueIdAndName(venueId, "KSPO DOME") } returns true

            val exception = assertThrows<EventException> {
                hallService.createHall(venueId, request)
            }
            assertEquals(ErrorCode.HALL_NAME_DUPLICATE, exception.errorCode)
        }

        @Test
        @DisplayName("중복 행 이름이 있으면 예외가 발생한다")
        fun duplicateRowNames() {
            val venue = createVenue()
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = SeatTemplateDto(
                    rows = listOf("A", "A", "B"),
                    seatsPerRow = 10,
                    gradeMapping = mapOf("A" to "VIP", "B" to "S")
                )
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.existsByVenueIdAndName(venueId, "KSPO DOME") } returns false

            val exception = assertThrows<EventException> {
                hallService.createHall(venueId, request)
            }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, exception.errorCode)
        }

        @Test
        @DisplayName("gradeMapping에 매핑되지 않은 행이 있으면 예외가 발생한다")
        fun unmappedRow() {
            val venue = createVenue()
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = SeatTemplateDto(
                    rows = listOf("A", "B", "C"),
                    seatsPerRow = 10,
                    gradeMapping = mapOf("A" to "VIP", "B" to "S")
                )
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.existsByVenueIdAndName(venueId, "KSPO DOME") } returns false

            val exception = assertThrows<EventException> {
                hallService.createHall(venueId, request)
            }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, exception.errorCode)
        }

        @Test
        @DisplayName("gradeMapping에 잉여 키가 있으면 예외가 발생한다")
        fun orphanGradeMappingKeys() {
            val venue = createVenue()
            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = SeatTemplateDto(
                    rows = listOf("A", "B"),
                    seatsPerRow = 10,
                    gradeMapping = mapOf("A" to "VIP", "B" to "S", "C" to "A")
                )
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.existsByVenueIdAndName(venueId, "KSPO DOME") } returns false

            val exception = assertThrows<EventException> {
                hallService.createHall(venueId, request)
            }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("getHalls")
    inner class GetHalls {

        @Test
        @DisplayName("공연장의 홀 목록을 조회한다")
        fun success() {
            val hall = createHall()
            every { venueRepository.existsById(venueId) } returns true
            every { hallRepository.findByVenueId(venueId) } returns listOf(hall)

            val result = hallService.getHalls(venueId)

            assertEquals(1, result.size)
            assertEquals("KSPO DOME", result[0].name)
        }

        @Test
        @DisplayName("존재하지 않는 공연장의 홀 목록 조회 시 예외가 발생한다")
        fun venueNotFound() {
            every { venueRepository.existsById(venueId) } returns false

            val exception = assertThrows<EventException> {
                hallService.getHalls(venueId)
            }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("getHall")
    inner class GetHall {

        @Test
        @DisplayName("홀 상세를 조회한다")
        fun success() {
            val hall = createHall()
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            val result = hallService.getHall(venueId, hallId)

            assertEquals(hallId, result.id)
            assertEquals("KSPO DOME", result.name)
            assertEquals(seatTemplateDto.rows, result.seatTemplate.rows)
            assertEquals(seatTemplateDto.seatsPerRow, result.seatTemplate.seatsPerRow)
        }

        @Test
        @DisplayName("존재하지 않는 홀 조회 시 예외가 발생한다")
        fun notFound() {
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns null

            val exception = assertThrows<EventException> {
                hallService.getHall(venueId, hallId)
            }
            assertEquals(ErrorCode.HALL_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("손상된 JSON seatTemplate 조회 시 예외가 발생한다")
        fun invalidSeatTemplateJson() {
            val hall = createHall(seatTemplate = "invalid-json")
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            val exception = assertThrows<EventException> {
                hallService.getHall(venueId, hallId)
            }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE, exception.errorCode)
        }

        @Test
        @DisplayName("캐시 Hit - DB 조회 없이 캐시에서 즉시 반환한다")
        fun cacheHit() {
            val hall = createHall()
            val detailResponse = HallDto.DetailResponse.from(hall, seatTemplateDto)
            val cacheKey = "cache:layout:$hallId"
            val cachedJson = objectMapper.writeValueAsString(detailResponse)

            every { valueOps.get(cacheKey) } returns cachedJson

            val result = hallService.getHall(venueId, hallId)

            assertEquals(hallId, result.id)
            verify(exactly = 0) { hallRepository.findByVenueIdAndId(any(), any()) }
        }

        @Test
        @DisplayName("캐시 Miss - DB 조회 후 캐시에 저장한다")
        fun cacheMissWritesToCache() {
            val hall = createHall()
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            hallService.getHall(venueId, hallId)

            verify { valueOps.set("cache:layout:$hallId", any(), eq(Duration.ofHours(24))) }
        }

        @Test
        @DisplayName("Redis 장애 시 DB fallback으로 정상 응답한다")
        fun redisFallback() {
            val hall = createHall()
            every { valueOps.get(any<String>()) } throws RedisConnectionFailureException("connection failed")
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            val result = hallService.getHall(venueId, hallId)

            assertEquals(hallId, result.id)
        }
    }

    @Nested
    @DisplayName("updateHall")
    inner class UpdateHall {

        @Test
        @DisplayName("전체 필드를 업데이트한다")
        fun fullUpdate() {
            val hall = createHall()
            val newTemplate = SeatTemplateDto(
                rows = listOf("A", "B"),
                seatsPerRow = 20,
                gradeMapping = mapOf("A" to "VIP", "B" to "S")
            )
            val request = HallDto.UpdateRequest(
                name = "올림픽홀",
                capacity = 3000,
                seatTemplate = newTemplate
            )
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall
            every { hallRepository.existsByVenueIdAndNameExcluding(venueId, "올림픽홀", hallId) } returns false

            val result = hallService.updateHall(venueId, hallId, request)

            assertEquals("올림픽홀", result.name)
            assertEquals(3000, result.capacity)
        }

        @Test
        @DisplayName("부분 필드만 업데이트한다")
        fun partialUpdate() {
            val hall = createHall()
            val request = HallDto.UpdateRequest(capacity = 20000)
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            val result = hallService.updateHall(venueId, hallId, request)

            assertEquals("KSPO DOME", result.name)
            assertEquals(20000, result.capacity)
        }

        @Test
        @DisplayName("존재하지 않는 홀 업데이트 시 예외가 발생한다")
        fun notFound() {
            val request = HallDto.UpdateRequest(name = "올림픽홀")
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns null

            val exception = assertThrows<EventException> {
                hallService.updateHall(venueId, hallId, request)
            }
            assertEquals(ErrorCode.HALL_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("이름 변경 시 중복되면 예외가 발생한다")
        fun duplicateName() {
            val hall = createHall()
            val request = HallDto.UpdateRequest(name = "올림픽홀")
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall
            every { hallRepository.existsByVenueIdAndNameExcluding(venueId, "올림픽홀", hallId) } returns true

            val exception = assertThrows<EventException> {
                hallService.updateHall(venueId, hallId, request)
            }
            assertEquals(ErrorCode.HALL_NAME_DUPLICATE, exception.errorCode)
        }

        @Test
        @DisplayName("수정 성공 시 좌석 배치도 캐시를 무효화한다")
        fun cacheInvalidationOnUpdate() {
            val hall = createHall()
            val request = HallDto.UpdateRequest(capacity = 20000)
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall

            hallService.updateHall(venueId, hallId, request)

            verify { redisTemplate.delete("cache:layout:$hallId") }
        }
    }

    @Nested
    @DisplayName("deleteHall")
    inner class DeleteHall {

        @Test
        @DisplayName("성공적으로 홀을 삭제한다")
        fun success() {
            val hall = createHall()
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall
            every { eventRepository.existsByHallId(hallId) } returns false
            every { hallRepository.delete(hall) } returns Unit

            val result = hallService.deleteHall(venueId, hallId)

            assertNotNull(result.message)
            verify { hallRepository.delete(hall) }
        }

        @Test
        @DisplayName("존재하지 않는 홀 삭제 시 예외가 발생한다")
        fun notFound() {
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns null

            val exception = assertThrows<EventException> {
                hallService.deleteHall(venueId, hallId)
            }
            assertEquals(ErrorCode.HALL_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("공연이 존재하는 홀 삭제 시 예외가 발생한다")
        fun hasEvents() {
            val hall = createHall()
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall
            every { eventRepository.existsByHallId(hallId) } returns true

            val exception = assertThrows<EventException> {
                hallService.deleteHall(venueId, hallId)
            }
            assertEquals(ErrorCode.HALL_HAS_EVENTS, exception.errorCode)
        }

        @Test
        @DisplayName("삭제 성공 시 좌석 배치도 캐시를 무효화한다")
        fun cacheInvalidationOnDelete() {
            val hall = createHall()
            every { hallRepository.findByVenueIdAndId(venueId, hallId) } returns hall
            every { eventRepository.existsByHallId(hallId) } returns false
            every { hallRepository.delete(hall) } returns Unit

            hallService.deleteHall(venueId, hallId)

            verify { redisTemplate.delete("cache:layout:$hallId") }
        }
    }
}
