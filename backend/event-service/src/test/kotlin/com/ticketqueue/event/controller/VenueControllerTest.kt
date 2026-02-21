package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.VenueDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.VenueService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime
import java.util.UUID

class VenueControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var venueService: VenueService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val venueId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun venueResponse() = VenueDto.Response(
        id = venueId,
        name = "올림픽공원",
        address = "서울시 송파구",
        city = "서울",
        createdAt = now
    )

    @BeforeEach
    fun setUp() {
        venueService = mockk()
        val controller = VenueController(venueService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("POST /venues")
    inner class CreateVenue {

        @Test
        @DisplayName("201 Created - 관리자가 공연장을 생성한다")
        fun created() {
            val request = VenueDto.CreateRequest(
                name = "올림픽공원",
                address = "서울시 송파구",
                city = "서울"
            )
            every { venueService.createVenue(any()) } returns venueResponse()

            mockMvc.perform(
                post("/venues")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("올림픽공원"))
        }

        @Test
        @DisplayName("400 Bad Request - 유효성 검사 실패")
        fun badRequest() {
            val request = mapOf("name" to "", "address" to "", "city" to "")

            mockMvc.perform(
                post("/venues")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isBadRequest)
        }
    }

    @Nested
    @DisplayName("GET /venues")
    inner class GetVenues {

        @Test
        @DisplayName("200 OK - 공연장 목록을 조회한다")
        fun list() {
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(listOf(venueResponse()), pageable, 1)
            every { venueService.getVenues(0, 20, null) } returns page

            mockMvc.perform(get("/venues"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.list[0].name").value("올림픽공원"))
                .andExpect(jsonPath("$.totalElements").value(1))
        }

        @Test
        @DisplayName("200 OK - 도시 필터로 공연장 목록을 조회한다")
        fun listWithCityFilter() {
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(listOf(venueResponse()), pageable, 1)
            every { venueService.getVenues(0, 20, "서울") } returns page

            mockMvc.perform(get("/venues").param("city", "서울"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.list[0].city").value("서울"))
        }
    }

    @Nested
    @DisplayName("GET /venues/{venueId}")
    inner class GetVenue {

        @Test
        @DisplayName("200 OK - 공연장 상세를 조회한다")
        fun detail() {
            val detail = VenueDto.DetailResponse(
                id = venueId,
                name = "올림픽공원",
                address = "서울시 송파구",
                city = "서울",
                halls = emptyList(),
                createdAt = now,
                updatedAt = now
            )
            every { venueService.getVenue(venueId) } returns detail

            mockMvc.perform(get("/venues/{venueId}", venueId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("올림픽공원"))
                .andExpect(jsonPath("$.halls").isArray)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연장")
        fun notFound() {
            every { venueService.getVenue(venueId) } throws EventException(ErrorCode.VENUE_NOT_FOUND)

            mockMvc.perform(get("/venues/{venueId}", venueId))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("PATCH /venues/{venueId}")
    inner class UpdateVenue {

        @Test
        @DisplayName("200 OK - 공연장을 수정한다")
        fun success() {
            val response = VenueDto.UpdateResponse(
                id = venueId,
                name = "고척스카이돔",
                address = "서울시 송파구",
                city = "서울",
                updatedAt = now
            )
            every { venueService.updateVenue(venueId, any()) } returns response

            val request = VenueDto.UpdateRequest(name = "고척스카이돔")
            mockMvc.perform(
                patch("/venues/{venueId}", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("고척스카이돔"))
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연장 수정")
        fun notFound() {
            every { venueService.updateVenue(venueId, any()) } throws EventException(ErrorCode.VENUE_NOT_FOUND)

            val request = VenueDto.UpdateRequest(name = "고척스카이돔")
            mockMvc.perform(
                patch("/venues/{venueId}", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("DELETE /venues/{venueId}")
    inner class DeleteVenue {

        @Test
        @DisplayName("200 OK - 공연장을 삭제한다")
        fun success() {
            every { venueService.deleteVenue(venueId) } returns VenueDto.DeleteResponse("공연장이 삭제되었습니다.")

            mockMvc.perform(delete("/venues/{venueId}", venueId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("공연장이 삭제되었습니다."))
        }

        @Test
        @DisplayName("409 Conflict - 공연이 존재하는 공연장 삭제 시")
        fun conflict() {
            every { venueService.deleteVenue(venueId) } throws EventException(ErrorCode.VENUE_HAS_EVENTS)

            mockMvc.perform(delete("/venues/{venueId}", venueId))
                .andExpect(status().isConflict)
        }

        @Test
        @DisplayName("409 Conflict - 홀이 존재하는 공연장 삭제 시")
        fun conflictHalls() {
            every { venueService.deleteVenue(venueId) } throws EventException(ErrorCode.VENUE_HAS_HALLS)

            mockMvc.perform(delete("/venues/{venueId}", venueId))
                .andExpect(status().isConflict)
        }
    }
}
