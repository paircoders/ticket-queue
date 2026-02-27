package com.ticketqueue.event.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Venue
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime

/**
 * EventScheduleRepository 통합 테스트
 *
 * existsByEventIdAndPlaySequence 메서드를 PostgreSQL 18 환경에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ScheduleRepositoryIntegrationTest.RepositoryTestConfig::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@Transactional
class ScheduleRepositoryIntegrationTest {

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

    private fun saveSchedule(event: Event, playSequence: Int): EventSchedule =
        eventScheduleRepository.save(
            EventSchedule(
                event = event, playSequence = playSequence,
                eventStartAt = now.plusDays(30),
                eventEndAt = now.plusDays(30).plusHours(2),
                saleStartAt = now.plusDays(1),
                saleEndAt = now.plusDays(29)
            )
        )

    @Nested
    @DisplayName("existsByEventIdAndPlaySequence")
    @Transactional
    inner class ExistsByEventIdAndPlaySequence {

        @Test
        @DisplayName("동일 공연에 동일 순번의 회차가 존재하면 true를 반환한다")
        fun existsReturnsTrue() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, playSequence = 1)

            val result = eventScheduleRepository.existsByEventIdAndPlaySequence(event.id!!, 1)

            assertTrue(result)
        }

        @Test
        @DisplayName("동일 공연에 해당 순번의 회차가 없으면 false를 반환한다")
        fun notExistsReturnsFalse() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)
            saveSchedule(event, playSequence = 1)

            val result = eventScheduleRepository.existsByEventIdAndPlaySequence(event.id!!, 2)

            assertFalse(result)
        }

        @Test
        @DisplayName("다른 공연의 동일 순번은 false를 반환한다")
        fun differentEventReturnsFalse() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event1 = saveEvent(venue, hall)
            val event2 = saveEvent(venue, hall)
            saveSchedule(event1, playSequence = 1)

            val result = eventScheduleRepository.existsByEventIdAndPlaySequence(event2.id!!, 1)

            assertFalse(result)
        }

        @Test
        @DisplayName("회차가 없는 공연은 false를 반환한다")
        fun noSchedulesReturnsFalse() {
            val venue = saveVenue()
            val hall = saveHall(venue)
            val event = saveEvent(venue, hall)

            val result = eventScheduleRepository.existsByEventIdAndPlaySequence(event.id!!, 1)

            assertFalse(result)
        }
    }
}
