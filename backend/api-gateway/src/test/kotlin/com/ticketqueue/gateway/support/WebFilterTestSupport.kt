package com.ticketqueue.gateway.support

import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * WebFilter 단위 테스트 공통 헬퍼
 *
 * JwtAuthenticationWebFilterUnitTest, QueueTokenWebFilterUnitTest 등에서 공유.
 */
fun exchange(
    method: HttpMethod,
    path: String,
    block: MockServerHttpRequest.BaseBuilder<*>.() -> Unit = {},
): MockServerWebExchange {
    val builder = when (method) {
        HttpMethod.GET -> MockServerHttpRequest.get(path)
        HttpMethod.POST -> MockServerHttpRequest.post(path)
        HttpMethod.PUT -> MockServerHttpRequest.put(path)
        HttpMethod.DELETE -> MockServerHttpRequest.delete(path)
        HttpMethod.OPTIONS -> MockServerHttpRequest.options(path)
        else -> error("Unsupported HTTP method: $method")
    }
    block(builder)
    return MockServerWebExchange.from(builder.build())
}

fun responseBody(exchange: MockServerWebExchange): String =
    exchange.response.bodyAsString.block() ?: ""
