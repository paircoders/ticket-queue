package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.Event
import jakarta.persistence.EntityManagerFactory
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.entity.Venue
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Event Repository QueryDSL 통합 테스트
 *
 * - @DataJpaTest + @AutoConfigureTestDatabase(replace=NONE): H2 대신 실제 PostgreSQL 사용
 * - @Testcontainers + @ServiceConnection: 컨테이너 ↔ Spring Datasource 자동 연결
 * - withInitScript: 스키마 먼저 생성 → Hibernate create-drop으로 테이블 관리
 * - 각 테스트는 독립 트랜잭션 + 자동 롤백으로 격리됨
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(EventRepositoryIntegrationTest.RepositoryTestConfig::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@Transactional // 각 테스트 메서드가 독립 트랜잭션 + 롤백으로 실행되어 데이터 격리 보장
class EventRepositoryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))
            .apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
                withInitScript("db_init/init.sql") // CREATE SCHEMA event_service 선행 실행
            }
    }

    /**
     * @DataJpaTest는 JPAQueryFactory를 자동 등록하지 않으므로 수동 제공 필요.
     *
     * SharedEntityManagerCreator를 사용하는 이유:
     * - 일반 EntityManager 직접 주입 시, JPAQueryFactory는 트랜잭션 외부의 EM을 사용하게 됨
     * - 이 경우 @DataJpaTest의 @Transactional 롤백이 제대로 동작하지 않아 테스트 간 데이터가 누적됨
     * - SharedEntityManagerCreator는 현재 스레드에 바인딩된 트랜잭션 EM을 사용하는 프록시를 생성
     * - → QueryDSL 쿼리가 테스트의 @Transactional 컨텍스트 안에서 실행되어 데이터 격리 보장
     */
    @TestConfiguration
    class RepositoryTestConfig {
        @Bean
        fun jpaQueryFactory(emf: EntityManagerFactory): JPAQueryFactory =
            JPAQueryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
    }

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var eventRepository: EventRepository
    @Autowired private lateinit var venueRepository: VenueRepository
    @Autowired private lateinit var hallRepository: HallRepository
    @Autowired private lateinit var eventScheduleRepository: EventScheduleRepository
    @Autowired private lateinit var seatRepository: SeatRepository

    private val now: LocalDateTime = LocalDateTime.now()

    /**
     * 각 테스트 메서드 실행 전 모든 테이블을 정리한다.
     *
     * @Nested 클래스에서 Spring TestContext의 @Transactional 롤백이 올바르게 동작하지 않을 수 있으므로,
     * JUnit 5의 @BeforeEach 상속 + raw JDBC 자동 커밋으로 데이터 격리를 보장한다.
     * dataSource.connection은 Spring의 트랜잭션 관리 외부에서 별도 연결을 획득하므로 즉시 커밋된다.
     */
    @BeforeEach
    fun cleanAll() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM event_service.seats")
                stmt.execute("DELETE FROM event_service.event_schedules")
                stmt.execute("DELETE FROM event_service.events")
                stmt.execute("DELETE FROM event_service.halls")
                stmt.execute("DELETE FROM event_service.venues")
            }
        }
    }

    // ──────── 테스트 데이터 헬퍼 ────────

    private fun saveVenue(name: String = "올림픽공원", city: String = "서울"): Venue =
        venueRepository.save(Venue(name = name, address = "서울시 송파구 올림픽로 25", city = city))

    private fun saveHall(venue: Venue, name: String = "메인 홀"): Hall =
        hallRepository.save(
            Hall(
                venue = venue, name = name, capacity = 15000,
                seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}"""
            )
        )

    private fun saveEvent(
        venue: Venue,
        hall: Hall,
        title: String = "BTS World Tour",
        artist: String = "BTS",
        status: EventStatus = EventStatus.PREPARING,
        deleted: Boolean = false
    ): Event {
        val event = eventRepository.save(Event(title = title, artist = artist, venue = venue, hall = hall))
        if (status != EventStatus.PREPARING) event.changeStatus(status)
        if (deleted) event.softDelete()
        return if (status != EventStatus.PREPARING || deleted) eventRepository.save(event) else event
    }

    private fun saveSchedule(
        event: Event,
        playSequence: Int = 1,
        eventStartAt: LocalDateTime = now.plusDays(30),
        saleStartAt: LocalDateTime = now.plusDays(1)
    ): EventSchedule = eventScheduleRepository.save(
        EventSchedule(
            event = event, playSequence = playSequence,
            eventStartAt = eventStartAt,
            eventEndAt = eventStartAt.plusHours(2),
            saleStartAt = saleStartAt,
            saleEndAt = saleStartAt.plusDays(28)
        )
    )

    private fun saveSeat(
        schedule: EventSchedule,
        seatNumber: String = "A-1",
        status: SeatStatus = SeatStatus.AVAILABLE
    ): Seat = seatRepository.save(
        Seat(
            eventSchedule = schedule, seatNumber = seatNumber,
            grade = SeatGrade.VIP, price = BigDecimal("150000"), status = status
        )
    )

    // ──────── findEventList ────────

    @Nested
    @DisplayName("findEventList")
    @Transactional
    inner class FindEventList {

        @Test
        @DisplayName("삭제된 공연은 목록에 포함되지 않는다")
        fun deletedEventExcluded() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            saveEvent(venue, hall, title = "삭제된 공연", deleted = true)
            saveEvent(venue, hall, title = "활성 공연")

            val result = eventRepository.findEventList(PageRequest.of(0, 20), null, null, null)

            assertTrue(result.content.none { it.title == "삭제된 공연" })
            assertTrue(result.content.any { it.title == "활성 공연" })
        }

        @Test
        @DisplayName("status 필터가 동작한다")
        fun statusFilter() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            saveEvent(venue, hall, title = "PREPARING 공연", status = EventStatus.PREPARING)
            saveEvent(venue, hall, title = "OPEN 공연", status = EventStatus.OPEN)

            val result = eventRepository.findEventList(PageRequest.of(0, 20), EventStatus.OPEN, null, null)

            assertEquals(1, result.totalElements)
            assertEquals(EventStatus.OPEN, result.content[0].status)
        }

        @Test
        @DisplayName("city 필터가 동작한다")
        fun cityFilter() {
            val seoulVenue = saveVenue(name = "서울공연장", city = "서울")
            val busanVenue = saveVenue(name = "부산공연장", city = "부산")
            val hall1 = saveHall(seoulVenue)
            val hall2 = saveHall(busanVenue)
            saveEvent(seoulVenue, hall1, title = "서울 공연")
            saveEvent(busanVenue, hall2, title = "부산 공연")

            val result = eventRepository.findEventList(PageRequest.of(0, 20), null, "서울", null)

            assertEquals(1, result.totalElements)
            assertEquals("서울 공연", result.content[0].title)
        }

        @Test
        @DisplayName("keyword 검색이 동작한다 (LIKE 기반, FTS 동작 방식 동일)")
        fun keywordFtsSearch() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            saveEvent(venue, hall, title = "BTS World Tour", artist = "BTS")
            saveEvent(venue, hall, title = "블랙핑크 콘서트", artist = "BLACKPINK")

            val result = eventRepository.findEventList(PageRequest.of(0, 20), null, null, "BTS")

            assertEquals(1, result.totalElements)
            assertEquals("BTS World Tour", result.content[0].title)
        }

        @Test
        @DisplayName("회차의 MIN/MAX startDate가 집계된다")
        fun scheduleAggregation() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, 1, eventStartAt = now.plusDays(30))
            saveSchedule(event, 2, eventStartAt = now.plusDays(60))

            val result = eventRepository.findEventList(PageRequest.of(0, 20), null, null, null)

            assertEquals(1, result.totalElements)
            val item = result.content[0]
            assertNotNull(item.startDate)
            assertNotNull(item.endDate)
            // MIN = plusDays(30), MAX = plusDays(60) → startDate < endDate
            assertTrue(item.startDate!!.isBefore(item.endDate!!))
        }

        @Test
        @DisplayName("페이지네이션이 동작한다")
        fun pagination() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            repeat(5) { i -> saveEvent(venue, hall, title = "공연$i") }

            val page0 = eventRepository.findEventList(PageRequest.of(0, 3), null, null, null)
            val page1 = eventRepository.findEventList(PageRequest.of(1, 3), null, null, null)

            assertEquals(5, page0.totalElements)
            assertEquals(3, page0.content.size)
            assertEquals(2, page1.content.size)
        }
    }

    // ──────── findEventWithVenueAndHall ────────

    @Nested
    @DisplayName("findEventWithVenueAndHall")
    @Transactional
    inner class FindEventWithVenueAndHall {

        @Test
        @DisplayName("venue와 hall을 페치 조인으로 함께 조회한다")
        fun fetchJoin() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)

            val result = eventRepository.findEventWithVenueAndHall(event.id!!)

            assertNotNull(result)
            assertEquals(event.id, result!!.id)
            assertEquals(venue.id, result.venue.id)
            assertEquals(hall.id, result.hall.id)
        }

        @Test
        @DisplayName("deletedAt이 설정된 공연은 null을 반환한다")
        fun deletedReturnsNull() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall, deleted = true)

            val result = eventRepository.findEventWithVenueAndHall(event.id!!)

            assertNull(result)
        }

        @Test
        @DisplayName("존재하지 않는 ID는 null을 반환한다")
        fun notFoundReturnsNull() {
            val result = eventRepository.findEventWithVenueAndHall(UUID.randomUUID())
            assertNull(result)
        }
    }

    // ──────── findScheduleIdsWithAvailableSeats ────────

    @Nested
    @DisplayName("findScheduleIdsWithAvailableSeats")
    @Transactional
    inner class FindScheduleIdsWithAvailableSeats {

        @Test
        @DisplayName("AVAILABLE 좌석이 있는 회차 ID만 반환된다")
        fun onlyAvailableScheduleIds() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            val schedule1 = saveSchedule(event, 1)
            val schedule2 = saveSchedule(event, 2)
            saveSeat(schedule1, "A-1", SeatStatus.AVAILABLE)
            saveSeat(schedule2, "A-1", SeatStatus.SOLD) // schedule2는 SOLD 좌석만

            val result = eventRepository.findScheduleIdsWithAvailableSeats(
                listOf(schedule1.id!!, schedule2.id!!)
            )

            assertTrue(schedule1.id!! in result)
            assertFalse(schedule2.id!! in result)
        }

        @Test
        @DisplayName("모든 좌석이 SOLD이면 빈 Set을 반환한다")
        fun allSoldReturnsEmpty() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            val schedule = saveSchedule(event)
            saveSeat(schedule, "A-1", SeatStatus.SOLD)
            saveSeat(schedule, "A-2", SeatStatus.SOLD)

            val result = eventRepository.findScheduleIdsWithAvailableSeats(listOf(schedule.id!!))

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("빈 scheduleIds 입력 시 빈 Set을 반환한다")
        fun emptyInputReturnsEmpty() {
            val result = eventRepository.findScheduleIdsWithAvailableSeats(emptyList())
            assertTrue(result.isEmpty())
        }
    }

    // ──────── findByIdForUpdate ────────

    @Nested
    @DisplayName("findByIdForUpdate")
    @Transactional
    inner class FindByIdForUpdate {

        @Test
        @DisplayName("비관적 락으로 공연 엔티티를 반환한다")
        fun returnsEventWithLock() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)

            val result = eventRepository.findByIdForUpdate(event.id!!)

            assertNotNull(result)
            assertEquals(event.id, result!!.id)
        }

        @Test
        @DisplayName("존재하지 않는 ID는 null을 반환한다")
        fun notFoundReturnsNull() {
            val result = eventRepository.findByIdForUpdate(UUID.randomUUID())
            assertNull(result)
        }

        @Test
        @DisplayName("소프트 삭제된 공연은 findByIdForUpdate에서 null을 반환한다")
        fun deletedEventIsNotReturned() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall, deleted = true)

            // findByIdForUpdate는 deletedAt IS NULL 필터를 포함하므로 soft-deleted 이벤트는 null 반환
            val result = eventRepository.findByIdForUpdate(event.id!!)

            assertNull(result)
        }
    }

    // ──────── existsByEventIdAndSaleStartAtLessThanEqual ────────

    @Nested
    @DisplayName("existsByEventIdAndSaleStartAtLessThanEqual")
    @Transactional
    inner class ExistsByEventIdAndSaleStartAtBefore {

        @Test
        @DisplayName("판매가 이미 시작된 회차가 있으면 true를 반환한다")
        fun saleAlreadyStarted() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, saleStartAt = now.minusHours(1)) // 1시간 전 판매 시작

            val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(event.id!!, now)

            assertTrue(result)
        }

        @Test
        @DisplayName("모든 회차가 판매 시작 전이면 false를 반환한다")
        fun saleNotYetStarted() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, saleStartAt = now.plusDays(1)) // 내일 판매 시작

            val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(event.id!!, now)

            assertFalse(result)
        }

        @Test
        @DisplayName("회차가 없는 공연은 false를 반환한다")
        fun noSchedulesReturnsFalse() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            // 회차 없음

            val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(event.id!!, now)

            assertFalse(result)
        }
    }
}
