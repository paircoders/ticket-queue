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
import org.springframework.test.context.TestPropertySource
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
            classes = [AuthController::class, GlobalExceptionHandler::class, SecurityConfig::class]
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
@ContextConfiguration(classes = [AuthController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("AuthController 단위 테스트")
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var authService: AuthService

    private fun performPost(url: String, content: Any?) = mockMvc.perform(
        post(url)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .let { if (content != null) it.content(objectMapper.writeValueAsString(content)) else it }
    )

    @Nested
    @DisplayName("POST /auth/signup")
    inner class SignupTest {

        private val signupUrl = "/auth/signup"
        private val validRequest = AuthDto.SignupRequest(
            email = "test@example.com",
            password = "securePassword123!",
            name = "홍길동",
            phone = "01012345678",
            identityVerificationId = "test_verification_id",
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
            performPost(signupUrl, validRequest)
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
            performPost(signupUrl, validRequest)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(validRequest.email))
                .andExpect(jsonPath("$.name").value(validRequest.name))
        }

        @Test
        @DisplayName("입력값 검증 오류 시 400 BAD REQUEST 및 상세 메시지 확인")
        fun `should return 400 when validation fails`() {
            val cases = listOf(
                validRequest.copy(email = "") to "이메일",
                validRequest.copy(email = "invalid-email") to "이메일",
                validRequest.copy(password = "") to "비밀번호",
                validRequest.copy(name = "") to "이름",
                validRequest.copy(phone = "") to "전화번호",
                validRequest.copy(identityVerificationId = "") to "본인인증 ID",
                validRequest.copy(recaptchaToken = "") to "reCAPTCHA"
            )

            cases.forEach { (request, messagePart) ->
                performPost(signupUrl, request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messagePart)))
            }
        }

        @Test
        @DisplayName("이메일 중복 시 409 CONFLICT")
        fun `should return 409 when email already exists`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.ALREADY_EXISTS_EMAIL)

            // When & Then
            performPost(signupUrl, validRequest)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS_EMAIL"))
        }

        @Test
        @DisplayName("CI 중복 시 409 CONFLICT")
        fun `should return 409 when identity already exists`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.DUPLICATE_IDENTITY)

            // When & Then
            performPost(signupUrl, validRequest)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("DUPLICATE_IDENTITY"))
        }

        @Test
        @DisplayName("reCAPTCHA 검증 실패 시 400 BAD REQUEST")
        fun `should return 400 when recaptcha verification fails`() {
            // Given
            every { authService.signup(validRequest) } throws BusinessException(ErrorCode.RECAPTCHA_FAILED)

            // When & Then
            performPost(signupUrl, validRequest)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("RECAPTCHA_FAILED"))
        }

        @Test
        @DisplayName("요청 body 없을 시 400 BAD REQUEST")
        fun `should return 400 when request body is missing`() {
            // When & Then
            performPost(signupUrl, null)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
    }

    @Nested
    @DisplayName("POST /auth/login")
    inner class LoginTest {

        private val loginUrl = "/auth/login"
        private val validRequest = AuthDto.LoginRequest(
            email = "test@example.com",
            password = "securePassword123!",
            recaptchaToken = "valid_recaptcha_token"
        )
        private val validResponse = AuthDto.LoginResponse(
            accessToken = "mock-access-token",
            refreshToken = "mock-refresh-token",
            expiresIn = 3600L,
            tokenType = "Bearer"
        )

        @Test
        @DisplayName("유효한 요청 시 200 OK 응답")
        fun `should return 200 when valid request`() {
            every { authService.login(validRequest, any(), any()) } returns validResponse

            performPost(loginUrl, validRequest)
                .andExpect(status().isOk)
        }

        @Test
        @DisplayName("성공 응답에 accessToken, refreshToken, expiresIn, tokenType 포함")
        fun `should return token fields in response`() {
            every { authService.login(validRequest, any(), any()) } returns validResponse

            performPost(loginUrl, validRequest)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.accessToken").value("mock-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("mock-refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
        }

        @Test
        @DisplayName("입력값 검증 오류 시 400 BAD REQUEST")
        fun `should return 400 when validation fails`() {
            val cases = listOf(
                validRequest.copy(email = "") to "이메일",
                validRequest.copy(email = "invalid-email") to "이메일",
                validRequest.copy(password = "") to "비밀번호",
                validRequest.copy(recaptchaToken = "") to "reCAPTCHA"
            )

            cases.forEach { (request, messagePart) ->
                performPost(loginUrl, request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messagePart)))
            }
        }

        @Test
        @DisplayName("reCAPTCHA 실패 시 400 BAD REQUEST")
        fun `should return 400 when recaptcha fails`() {
            every { authService.login(validRequest, any(), any()) } throws BusinessException(ErrorCode.RECAPTCHA_FAILED)

            performPost(loginUrl, validRequest)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("RECAPTCHA_FAILED"))
        }

        @Test
        @DisplayName("이메일/비밀번호 불일치 시 401 UNAUTHORIZED")
        fun `should return 401 when credentials are invalid`() {
            every { authService.login(validRequest, any(), any()) } throws BusinessException(ErrorCode.INVALID_CREDENTIALS)

            performPost(loginUrl, validRequest)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        }

        @Test
        @DisplayName("요청 body 없을 시 400 BAD REQUEST")
        fun `should return 400 when request body is missing`() {
            performPost(loginUrl, null)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
    }

    @Nested
    @DisplayName("POST /auth/refresh")
    inner class RefreshTest {

        private val refreshUrl = "/auth/refresh"
        private val validRequest = AuthDto.RefreshRequest(refreshToken = "valid-refresh-token")
        private val validResponse = AuthDto.LoginResponse(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresIn = 3600L,
            tokenType = "Bearer"
        )

        @Test
        @DisplayName("유효한 요청 시 200 OK 응답")
        fun `should return 200 when valid request`() {
            every { authService.refresh(validRequest) } returns validResponse

            performPost(refreshUrl, validRequest)
                .andExpect(status().isOk)
        }

        @Test
        @DisplayName("성공 응답에 accessToken, refreshToken, expiresIn, tokenType 포함")
        fun `should return token fields in response`() {
            every { authService.refresh(validRequest) } returns validResponse

            performPost(refreshUrl, validRequest)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
        }

        @Test
        @DisplayName("빈 refreshToken 시 400 BAD REQUEST")
        fun `should return 400 when refreshToken is blank`() {
            performPost(refreshUrl, AuthDto.RefreshRequest(refreshToken = ""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("리프레시 토큰")))
        }

        @Test
        @DisplayName("요청 body 없을 시 400 BAD REQUEST")
        fun `should return 400 when request body is missing`() {
            performPost(refreshUrl, null)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }

        @Test
        @DisplayName("유효하지 않은 토큰 시 401 UNAUTHORIZED")
        fun `should return 401 when token is invalid`() {
            every { authService.refresh(validRequest) } throws BusinessException(ErrorCode.INVALID_TOKEN)

            performPost(refreshUrl, validRequest)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        }

        @Test
        @DisplayName("만료된 토큰 시 401 UNAUTHORIZED")
        fun `should return 401 when token is expired`() {
            every { authService.refresh(validRequest) } throws BusinessException(ErrorCode.EXPIRED_TOKEN)

            performPost(refreshUrl, validRequest)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"))
        }

        @Test
        @DisplayName("탈취 감지(폐기된 Refresh Token 재사용) 시 401 UNAUTHORIZED")
        fun `should return 401 when token is revoked`() {
            every { authService.refresh(validRequest) } throws BusinessException(ErrorCode.REVOKED_REFRESH_TOKEN)

            performPost(refreshUrl, validRequest)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"))
        }
    }
}
