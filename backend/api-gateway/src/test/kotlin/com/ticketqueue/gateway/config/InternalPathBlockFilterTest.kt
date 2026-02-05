package com.ticketqueue.gateway.config

import com.ticketqueue.gateway.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

class InternalPathBlockFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `internal path GET request should return 404`() {
        webTestClient.get()
            .uri("/internal/seats/status/1")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `internal path POST request should return 404`() {
        webTestClient.post()
            .uri("/internal/api/test")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `internal path PUT request should return 404`() {
        webTestClient.put()
            .uri("/internal/seats/status/1")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `internal path DELETE request should return 404`() {
        webTestClient.delete()
            .uri("/internal/api/test")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `deeply nested internal path should return 404`() {
        webTestClient.get()
            .uri("/internal/deep/nested/path/test")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `internal path with query parameters should return 404`() {
        webTestClient.get()
            .uri("/internal/test?param1=value1&param2=value2")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `public route should not be blocked - auth`() {
        webTestClient.get()
            .uri("/auth/login")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @Test
    fun `public route should not be blocked - events`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @Test
    fun `public route should not be blocked - queue`() {
        webTestClient.get()
            .uri("/queue/status")
            .exchange()
            .expectStatus().is5xxServerError
    }
}
