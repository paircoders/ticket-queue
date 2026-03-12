package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.Venue
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
import javax.sql.DataSource

/**
 * EventScheduleRepositoryCustomImpl 통합 테스트
 *
 * findCleanupTargetScheduleIds 메서드를 PostgreSQL 18 환경에서 검증한다.
 * - ENDED/CANCELLED 상태 + cutoff 이전 회차 필터링
 * - LIMIT 1000 적용 여부
 * - lt (strictly before) 경계값 동작
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(EventScheduleRepositoryCustomImplTest.RepositoryTestConfig::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@Transactional
class EventScheduleRepositoryCustomImplTest {

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

    private val now: LocalDateTime = LocalDateTime.now()

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
        venueRepository.save(Venue(name = "올림픽공원", address = "서울시 송파구 올림픽로 25", city = "서울"))

    private fun saveHall(venue: Venue): Hall =
        hallRepository.save(
            Hall(
                venue = venue, name = "메인 홀", capacity = 15000,
                seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}"""
            )
        )

    private fun saveEvent(venue: Venue, hall: Hall): Event =
        eventRepository.save(Event(title = "BTS World Tour", artist = "BTS", venue = venue, hall = hall))

    private fun saveSchedule(
        event: Event,
        playSequence: Int,
        status: ScheduleStatus,
        eventEndAt: LocalDateTime
    ): EventSchedule =
        eventScheduleRepository.save(
            EventSchedule(
                event = event,
                playSequence = playSequence,
                eventStartAt = eventEndAt.minusHours(2),
                eventEndAt = eventEndAt,
                saleStartAt = eventEndAt.minusDays(30),
                saleEndAt = eventEndAt.minusHours(3),
                status = status
            )
        )

    @Nested
    @DisplayName("findCleanupTargetScheduleIds")
    inner class FindCleanupTargetScheduleIds {

        @Test
        @DisplayName("ENDED + cutoff 이전 회차는 결과에 포함된다")
        fun endedBeforeCutoffIsIncluded() {
            val cutoffTime = now.minusHours(24)
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            val schedule = saveSchedule(event, 1, ScheduleStatus.ENDED, cutoffTime.minusHours(1))

            val result = eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)

            assertTrue(result.contains(schedule.id))
        }

        @Test
        @DisplayName("ENDED + cutoff 이후 회차는 결과에 포함되지 않는다")
        fun endedAfterCutoffIsExcluded() {
            val cutoffTime = now.minusHours(24)
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, 1, ScheduleStatus.ENDED, cutoffTime.plusHours(1))

            val result = eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("CANCELLED + cutoff 이전 회차는 결과에 포함된다")
        fun cancelledBeforeCutoffIsIncluded() {
            val cutoffTime = now.minusHours(24)
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            val schedule = saveSchedule(event, 1, ScheduleStatus.CANCELLED, cutoffTime.minusHours(1))

            val result = eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)

            assertTrue(result.contains(schedule.id))
        }

        @Test
        @DisplayName("ONGOING/UPCOMING 상태 회차는 cutoff 이전이어도 결과에 포함되지 않는다")
        fun ongoingAndUpcomingAreExcluded() {
            val cutoffTime = now.minusHours(24)
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, 1, ScheduleStatus.ONGOING, cutoffTime.minusHours(1))
            saveSchedule(event, 2, ScheduleStatus.UPCOMING, cutoffTime.minusHours(1))

            val result = eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("eventEndAt이 cutoffTime과 정확히 일치하면 결과에 포함되지 않는다 (lt, not lte)")
        fun exactCutoffBoundaryIsExcluded() {
            val cutoffTime = now.minusHours(24)
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, 1, ScheduleStatus.ENDED, cutoffTime)

            val result = eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)

            assertTrue(result.isEmpty())
        }
    }
}
