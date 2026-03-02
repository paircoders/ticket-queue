package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import com.ticketqueue.user.exception.UserException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient
import java.net.URI

class RecaptchaServiceTest {

    private lateinit var recaptchaService: RecaptchaService
    private val mockRestClient: RestClient = mockk()
    private val mockRestClientBuilder: RestClient.Builder = mockk()
    private val mockRequestBodyUriSpec: RestClient.RequestBodyUriSpec = mockk()
    private val mockRequestBodySpec: RestClient.RequestBodySpec = mockk()
    private val mockResponseSpec: RestClient.ResponseSpec = mockk()

    private val testUrl = "https://www.google.com/recaptcha/api/siteverify"
    private val testSecret = "test-secret"
    private val testToken = "test-token"

    @BeforeEach
    fun setUp() {
        every { mockRestClientBuilder.build() } returns mockRestClient

        recaptchaService = RecaptchaService(
            restClientBuilder = mockRestClientBuilder,
            recaptchaUrl = testUrl,
            recaptchaSecret = testSecret
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `검증 성공 - success true 반환`() {
        // Given
        val successResponse = RecaptchaService.RecaptchaResponse(success = true)

        every { mockRestClient.post() } returns mockRequestBodyUriSpec
        every { mockRequestBodyUriSpec.uri(any<URI>()) } returns mockRequestBodySpec
        every { mockRequestBodySpec.retrieve() } returns mockResponseSpec
        every { mockResponseSpec.body(any<ParameterizedTypeReference<RecaptchaService.RecaptchaResponse>>()) } returns successResponse

        // When
        val result = recaptchaService.verify(testToken)

        // Then
        assertTrue(result)
        verify(exactly = 1) {
            mockRestClient.post()
            mockRequestBodyUriSpec.uri(any<URI>())
            mockRequestBodySpec.retrieve()
            mockResponseSpec.body(any<ParameterizedTypeReference<RecaptchaService.RecaptchaResponse>>())
        }
    }

    @Test
    fun `검증 실패 - success false 반환`() {
        // Given
        val failedResponse = RecaptchaService.RecaptchaResponse(success = false)

        every { mockRestClient.post() } returns mockRequestBodyUriSpec
        every { mockRequestBodyUriSpec.uri(any<URI>()) } returns mockRequestBodySpec
        every { mockRequestBodySpec.retrieve() } returns mockResponseSpec
        every { mockResponseSpec.body(any<ParameterizedTypeReference<RecaptchaService.RecaptchaResponse>>()) } returns failedResponse

        // When
        val result = recaptchaService.verify(testToken)

        // Then
        assertFalse(result)
    }

    @Test
    fun `API 응답 null - false 반환`() {
        // Given
        every { mockRestClient.post() } returns mockRequestBodyUriSpec
        every { mockRequestBodyUriSpec.uri(any<URI>()) } returns mockRequestBodySpec
        every { mockRequestBodySpec.retrieve() } returns mockResponseSpec
        every { mockResponseSpec.body(any<ParameterizedTypeReference<RecaptchaService.RecaptchaResponse>>()) } returns null

        // When
        val result = recaptchaService.verify(testToken)

        // Then
        assertFalse(result)
    }

    @Test
    fun `올바른 URL과 파라미터 전달 검증`() {
        // Given
        val uriSlot = slot<URI>()
        val successResponse = RecaptchaService.RecaptchaResponse(success = true)

        every { mockRestClient.post() } returns mockRequestBodyUriSpec
        every { mockRequestBodyUriSpec.uri(capture(uriSlot)) } returns mockRequestBodySpec
        every { mockRequestBodySpec.retrieve() } returns mockResponseSpec
        every { mockResponseSpec.body(any<ParameterizedTypeReference<RecaptchaService.RecaptchaResponse>>()) } returns successResponse

        // When
        recaptchaService.verify(testToken)

        // Then
        val capturedUri = uriSlot.captured
        assertTrue(capturedUri.toString().contains("secret=$testSecret"))
        assertTrue(capturedUri.toString().contains("response=$testToken"))
        assertEquals(testUrl, capturedUri.scheme + "://" + capturedUri.authority + capturedUri.path)
    }

    @Test
    fun `API 호출 중 예외 발생 - 예외 전파`() {
        // Given
        val expectedException = RuntimeException("API 호출 실패")

        every { mockRestClient.post() } returns mockRequestBodyUriSpec
        every { mockRequestBodyUriSpec.uri(any<URI>()) } returns mockRequestBodySpec
        every { mockRequestBodySpec.retrieve() } throws expectedException

        // When & Then
        val exception = shouldThrow<ExternalSystemException> {
            recaptchaService.verify(testToken)
        }

        exception.errorCode shouldBe ErrorCode.RECAPTCHA_SERVICE_ERROR
    }
}
