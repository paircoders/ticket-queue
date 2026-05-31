package com.ticketqueue.common.external.portone

import io.mockk.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class PortoneTokenServiceTest {

    private val portoneClient = mockk<PortoneFeignClient>()
    private val portoneProperties = mockk<PortoneProperties>()
    private lateinit var portoneTokenService: PortoneTokenService

    @BeforeEach
    fun setup() {
        clearAllMocks()
        mockkStatic(Instant::class)
        every { portoneProperties.apiSecret } returns "test-api-secret"
        portoneTokenService = PortoneTokenService(portoneClient, portoneProperties)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Instant::class)
    }

    @Test
    fun `최초 호출 시 로그인을 통해 토큰을 발급받는다`() {
        // Given
        val now = Instant.parse("2026-02-20T10:00:00Z")
        every { Instant.now() } returns now
        
        val accessToken = "initial-access-token"
        val refreshToken = "initial-refresh-token"
        every { portoneClient.login(any()) } returns PortoneTokenResponse(accessToken, refreshToken)

        // When
        val token = portoneTokenService.getAccessToken()

        // Then
        token shouldBe "Bearer $accessToken"
        verify(exactly = 1) { portoneClient.login(any()) }
    }

    @Test
    fun `유효한 토큰이 있으면 기존 토큰을 반환한다`() {
        // Given
        val now = Instant.parse("2026-02-20T10:00:00Z")
        every { Instant.now() } returns now
        
        every { portoneClient.login(any()) } returns PortoneTokenResponse("access", "refresh")
        portoneTokenService.getAccessToken() // 최초 발급

        // 10분 후 (여전히 유효한 상태)
        every { Instant.now() } returns now.plusSeconds(600)

        // When
        val token = portoneTokenService.getAccessToken()

        // Then
        token shouldBe "Bearer access"
        verify(exactly = 1) { portoneClient.login(any()) }
    }

    @Test
    fun `토큰 로테이션 테스트 - 새로운 refresh 토큰이 응답에 포함되면 저장한다`() {
        // Given
        val initialAccess = "access-1"
        val initialRefresh = "refresh-1"
        val nextAccess = "access-2"
        val nextRefresh = "refresh-2"

        val now = Instant.parse("2026-02-20T10:00:00Z")
        val expiry = now.plusSeconds(1800)
        
        // 1. 최초 로그인
        every { Instant.now() } returns now
        every { portoneClient.login(any()) } returns PortoneTokenResponse(initialAccess, initialRefresh)
        portoneTokenService.getAccessToken()

        // 2. 만료 직전으로 시간 이동 (expiry - 60초)
        every { Instant.now() } returns expiry.minusSeconds(60)
        every { portoneClient.refreshToken(any()) } returns PortoneTokenResponse(nextAccess, nextRefresh)

        // When
        val token = portoneTokenService.getAccessToken()

        // Then
        token shouldBe "Bearer $nextAccess"
        
        // 3. 다시 한 번 만료 시 새로운 refresh 토큰(nextRefresh)을 사용하는지 확인
        val nextExpiry = expiry.minusSeconds(60).plusSeconds(1800)
        every { Instant.now() } returns nextExpiry.plusSeconds(100) // 다시 만료
        every { portoneClient.refreshToken(PortoneRefreshRequest(nextRefresh)) } returns PortoneTokenResponse("access-3", "refresh-3")
        
        val finalToken = portoneTokenService.getAccessToken()
        finalToken shouldBe "Bearer access-3"
        
        verify { portoneClient.refreshToken(PortoneRefreshRequest(nextRefresh)) }
    }

    @Test
    fun `토큰 갱신 응답에 refresh 토큰이 없으면 기존 것을 유지한다`() {
        // Given
        val initialRefresh = "initial-refresh"
        val now = Instant.parse("2026-02-20T10:00:00Z")
        every { Instant.now() } returns now
        
        every { portoneClient.login(any()) } returns PortoneTokenResponse("access-1", initialRefresh)
        portoneTokenService.getAccessToken()

        // 만료 유도
        val nextNow = now.plusSeconds(2000)
        every { Instant.now() } returns nextNow
        every { portoneClient.refreshToken(any()) } returns PortoneTokenResponse("access-2", null)

        // When
        portoneTokenService.getAccessToken()

        // Then
        // 다시 만료 유도
        every { Instant.now() } returns nextNow.plusSeconds(2000)
        every { portoneClient.refreshToken(PortoneRefreshRequest(initialRefresh)) } returns PortoneTokenResponse("access-3", null)
        
        portoneTokenService.getAccessToken()
        
        // 기존 initialRefresh가 계속 사용되어야 함
        verify(exactly = 2) { portoneClient.refreshToken(PortoneRefreshRequest(initialRefresh)) }
    }
    // TC-SEC-017: 동시성 검증 — 동시 10 스레드에서 login이 정확히 1회만 호출되는지 확인 (synchronized + double-check)
    @Test
    fun `동시 10개 스레드에서 최초 토큰 발급 시 login은 정확히 1회 호출된다`() {
        // Given
        val now = java.time.Instant.parse("2026-02-20T10:00:00Z")
        every { java.time.Instant.now() } returns now
        every { portoneClient.login(any()) } answers {
            Thread.sleep(10) // 경쟁 조건 유발
            PortoneTokenResponse("concurrent-access-token", "concurrent-refresh-token")
        }

        // When - 10개 스레드 동시 호출
        val threads = (1..10).map {
            Thread { portoneTokenService.getAccessToken() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Then - login은 정확히 1회만 호출되어야 함 (synchronized double-check)
        verify(exactly = 1) { portoneClient.login(any()) }
    }
}
