package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.Venue
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * EventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual 통합 테스트
 *
 * TC-EVT-005 회귀 방지: 판매 시작 후 artist 수정 차단 로직이 올바르게 동작하는지
 * 실제 PostgreSQL 환경에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(EventScheduleRepositoryTest.RepositoryTestConfig::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@Transactional
class EventScheduleRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))
            .apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
                withInitScript("db_init/init.sql")
            }
    }

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

    private val now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

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

    private fun saveVenue(): Venue =
        venueRepository.save(Venue(name = "올림픽공원", address = "서울시 송파구", city = "서울"))

    private fun saveHall(venue: Venue): Hall =
        hallRepository.save(
            Hall(
                venue = venue, name = "메인 홀", capacity = 100,
                seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}"""
            )
        )

    private fun saveEvent(venue: Venue, hall: Hall): Event =
        eventRepository.save(Event(title = "BTS 투어", artist = "BTS", venue = venue, hall = hall))

    private fun saveSchedule(
        event: Event,
        saleStartAt: LocalDateTime,
        saleEndAt: LocalDateTime = saleStartAt.plusMonths(1),
        eventStartAt: LocalDateTime = saleStartAt.plusMonths(2),
        eventEndAt: LocalDateTime = eventStartAt.plusHours(3),
        status: ScheduleStatus = ScheduleStatus.UPCOMING
    ): EventSchedule =
        eventScheduleRepository.save(
            EventSchedule(
                event = event, playSequence = 1,
                saleStartAt = saleStartAt, saleEndAt = saleEndAt,
                eventStartAt = eventStartAt, eventEndAt = eventEndAt,
                status = status
            )
        )

    @Test
    @DisplayName("판매 시작 이전인 회차가 있을 때 false를 반환한다")
    fun returnsFalseWhenSaleNotStarted() {
        val venue = saveVenue()
        val hall = saveHall(venue)
        val event = saveEvent(venue, hall)
        saveSchedule(event, saleStartAt = now.plusDays(1))

        val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(
            event.id!!, now
        )

        assertFalse(result)
    }

    @Test
    @DisplayName("판매가 이미 시작된 회차가 있을 때 true를 반환한다 — TC-EVT-005 핵심 검증")
    fun returnsTrueWhenSaleAlreadyStarted() {
        val venue = saveVenue()
        val hall = saveHall(venue)
        val event = saveEvent(venue, hall)
        // sale_start_at을 1시간 전으로 설정 — TC-EVT-005 재현 시나리오
        saveSchedule(event, saleStartAt = now.minusHours(1))

        val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(
            event.id!!, now
        )

        assertTrue(result, "판매가 1시간 전에 시작된 회차가 있으므로 true여야 한다")
    }

    @Test
    @DisplayName("sale_start_at이 기준 시각과 정확히 일치할 때 true를 반환한다 (LessThanEqual 경계값)")
    fun returnsTrueWhenSaleStartsAtExactly() {
        val venue = saveVenue()
        val hall = saveHall(venue)
        val event = saveEvent(venue, hall)
        saveSchedule(event, saleStartAt = now)

        val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(
            event.id!!, now
        )

        assertTrue(result, "sale_start_at == now이면 LessThanEqual이므로 true여야 한다")
    }

    @Test
    @DisplayName("다른 공연의 판매 시작 회차는 조회에 영향을 주지 않는다 (eventId 필터 격리)")
    fun doesNotCountOtherEventSchedules() {
        val venue = saveVenue()
        val hall = saveHall(venue)
        val event1 = saveEvent(venue, hall)
        val event2 = eventRepository.save(Event(title = "세나 투어", artist = "세나", venue = venue, hall = hall))
        // event2에 판매 시작된 회차가 있어도 event1 조회에는 영향 없음
        saveSchedule(event2, saleStartAt = now.minusHours(1))
        saveSchedule(event1, saleStartAt = now.plusDays(1)) // event1은 아직 판매 전

        val result = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(
            event1.id!!, now
        )

        assertFalse(result, "event1의 회차는 아직 판매 전이므로 false여야 한다")
    }
}
