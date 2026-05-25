package com.ticketqueue.user.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.user.config.SecurityConfig
import com.ticketqueue.user.dto.UserDto
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.service.UserService
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [UserController::class],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [UserController::class, GlobalExceptionHandler::class, SecurityConfig::class]
        )
    ],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration::class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration::class,
        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration::class,
        io.awspring.cloud.autoconfigure.config.secretsmanager.SecretsManagerAutoConfiguration::class,
        io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration::class
    ]
)
@ContextConfiguration(classes = [UserController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("UserController 단위 테스트")
class UserControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var userService: UserService

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        objectMapper.registerModule(JavaTimeModule())
    }

    @Nested
    @DisplayName("GET /users/me")
    inner class GetProfile {

        @Test
        @DisplayName("정상 요청 시 200 OK 및 프로필 반환")
        fun shouldReturn200() {
            val response = UserDto.ProfileResponse(
                id = userId,
                email = "test@example.com",
                name = "홍길동",
                phone = "010-1234-5678",
                role = UserRole.USER,
                createdAt = LocalDateTime.of(2026, 1, 1, 0, 0, 0),
            )
            every { userService.getProfile(userId) } returns response

            mockMvc.perform(
                get("/users/me")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.role").value("USER"))
        }

        @Test
        @DisplayName("인증 헤더 누락 시 401 UNAUTHORIZED")
        fun shouldReturn401WhenNoAuth() {
            mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized)
        }

        @Test
        @DisplayName("잘못된 Role 헤더 시 401 UNAUTHORIZED")
        fun shouldReturn401WhenInvalidRole() {
            mockMvc.perform(
                get("/users/me")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "GUEST")
            ).andExpect(status().isUnauthorized)
        }
    }

    @Nested
    @DisplayName("PATCH /users/me")
    inner class UpdateProfile {

        private val validRequest = UserDto.UpdateProfileRequest(
            name = "김철수",
            phone = "010-9876-5432",
        )

        private fun performPatch(content: Any?, includeAuth: Boolean = true) = mockMvc.perform(
            patch("/users/me")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .let { if (includeAuth) it.header("X-User-Id", userId.toString()).header("X-User-Role", "USER") else it }
                .let { if (content != null) it.content(objectMapper.writeValueAsString(content)) else it }
        )

        @Test
        @DisplayName("정상 수정 시 200 OK 및 수정된 필드 반환")
        fun shouldReturn200() {
            val response = UserDto.UpdateProfileResponse(
                id = userId,
                name = validRequest.name,
                phone = validRequest.phone,
            )
            every { userService.updateProfile(userId, validRequest) } returns response

            performPatch(validRequest)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.name").value(validRequest.name))
                .andExpect(jsonPath("$.phone").value(validRequest.phone))
        }

        @Test
        @DisplayName("이름 빈 값 시 400 BAD REQUEST")
        fun shouldReturn400WhenNameBlank() {
            performPatch(validRequest.copy(name = ""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이름")))
        }

        @Test
        @DisplayName("전화번호 형식 오류 시 400 BAD REQUEST")
        fun shouldReturn400WhenPhoneInvalid() {
            performPatch(validRequest.copy(phone = "abc-1234"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("전화번호")))
        }

        @Test
        @DisplayName("이름 너무 김 시 400 BAD REQUEST")
        fun shouldReturn400WhenNameTooLong() {
            performPatch(validRequest.copy(name = "가".repeat(51)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }

        @Test
        @DisplayName("인증 헤더 누락 시 401 UNAUTHORIZED")
        fun shouldReturn401WhenNoAuth() {
            performPatch(validRequest, includeAuth = false)
                .andExpect(status().isUnauthorized)
        }

        @Test
        @DisplayName("요청 body 없을 시 400 BAD REQUEST")
        fun shouldReturn400WhenBodyMissing() {
            performPatch(null)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }

        @Test
        @DisplayName("서비스에서 RESOURCE_NOT_FOUND 시 404 응답")
        fun shouldReturn404WhenUserMissing() {
            every { userService.updateProfile(userId, validRequest) } throws
                BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

            performPatch(validRequest)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }
    }
}
