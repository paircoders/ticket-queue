package com.ticketqueue.user.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.user.config.SecurityConfig
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.service.AuthService
import io.mockk.every
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [AuthController::class],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [AuthController::class, GlobalExceptionHandler::class]
        )
    ]
)
@ContextConfiguration(classes = [AuthController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@DisplayName("AuthController 단위 테스트")
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var authService: AuthService

    @Nested
    @DisplayName("POST /auth/signup")
    inner class SignupTest {

        private val validRequest = AuthDto.SignupRequest(
            email = "test@example.com",
            password = "securePassword123!",
            name = "홍길동",
            phone = "01012345678",
            ci = "ci_sample_12345",
            di = "di_sample_67890",
            recaptchaToken = "valid_recaptcha_token"
        )

        @Test
        @DisplayName("유효한 요청 시 201 CREATED 응답")
        fun `should return 201 when valid request`() {
            // Given
            val userId = UUID.randomUUID()
            val response = AuthDto.SignupResponse(
                id = userId,
                email = validRequest.email,
                name = validRequest.name
            )
            every { authService.signup(validRequest) } returns response

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest))
            )
                .andExpect(status().isCreated)
        }

        @Test
        @DisplayName("성공 응답에 id, email, name 포함")
        fun `should return id, email, name in response`() {
            // Given
            val userId = UUID.randomUUID()
            val response = AuthDto.SignupResponse(
                id = userId,
                email = validRequest.email,
                name = validRequest.name
            )
            every { authService.signup(validRequest) } returns response

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(validRequest.email))
                .andExpect(jsonPath("$.name").value(validRequest.name))
        }

        @Test
        @DisplayName("email 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when email is blank`() {
            // Given
            val invalidRequest = validRequest.copy(email = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이메일")))
        }

        @Test
        @DisplayName("email 형식 오류 시 400 BAD REQUEST")
        fun `should return 400 when email format is invalid`() {
            // Given
            val invalidRequest = validRequest.copy(email = "invalid-email")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이메일")))
        }

        @Test
        @DisplayName("password 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when password is blank`() {
            // Given
            val invalidRequest = validRequest.copy(password = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("비밀번호")))
        }

        @Test
        @DisplayName("name 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when name is blank`() {
            // Given
            val invalidRequest = validRequest.copy(name = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이름")))
        }

        @Test
        @DisplayName("phone 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when phone is blank`() {
            // Given
            val invalidRequest = validRequest.copy(phone = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("전화번호")))
        }

        @Test
        @DisplayName("ci 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when ci is blank`() {
            // Given
            val invalidRequest = validRequest.copy(ci = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CI")))
        }

        @Test
        @DisplayName("di 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when di is blank`() {
            // Given
            val invalidRequest = validRequest.copy(di = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DI")))
        }

        @Test
        @DisplayName("recaptchaToken 빈 값 시 400 BAD REQUEST")
        fun `should return 400 when recaptchaToken is blank`() {
            // Given
            val invalidRequest = validRequest.copy(recaptchaToken = "")

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reCAPTCHA")))
        }

        @Test
        @DisplayName("이메일 중복 시 409 CONFLICT")
        fun `should return 409 when email already exists`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.ALREADY_EXISTS_EMAIL)

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest))
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS_EMAIL"))
                .andExpect(jsonPath("$.message").exists())
        }

        @Test
        @DisplayName("CI 중복 시 409 CONFLICT")
        fun `should return 409 when ci already exists`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.ALREADY_EXISTS_USER)

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest))
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS_USER"))
                .andExpect(jsonPath("$.message").exists())
        }

        @Test
        @DisplayName("reCAPTCHA 검증 실패 시 400 BAD REQUEST")
        fun `should return 400 when recaptcha verification fails`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.RECAPTCHA_FAILED)

            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("RECAPTCHA_FAILED"))
                .andExpect(jsonPath("$.message").exists())
        }

        @Test
        @DisplayName("요청 body 없을 시 400 BAD REQUEST")
        fun `should return 400 when request body is missing`() {
            // When & Then
            mockMvc.perform(
                post("/auth/signup")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
    }
}
