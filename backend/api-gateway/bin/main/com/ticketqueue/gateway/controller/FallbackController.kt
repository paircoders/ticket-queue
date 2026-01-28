package com.ticketqueue.gateway.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/fallback")
class FallbackController {

    data class FallbackResponse(
        val message: String,
        val service: String
    )

    @GetMapping("/user")
    fun userServiceFallback(): Mono<ResponseEntity<FallbackResponse>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FallbackResponse("User service is currently unavailable", "user-service"))
        )
    }

    @GetMapping("/event")
    fun eventServiceFallback(): Mono<ResponseEntity<FallbackResponse>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FallbackResponse("Event service is currently unavailable", "event-service"))
        )
    }

    @GetMapping("/queue")
    fun queueServiceFallback(): Mono<ResponseEntity<FallbackResponse>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FallbackResponse("Queue service is currently unavailable", "queue-service"))
        )
    }

    @GetMapping("/reservation")
    fun reservationServiceFallback(): Mono<ResponseEntity<FallbackResponse>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FallbackResponse("Reservation service is currently unavailable", "reservation-service"))
        )
    }

    @GetMapping("/payment")
    fun paymentServiceFallback(): Mono<ResponseEntity<FallbackResponse>> {
        return Mono.just(
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FallbackResponse("Payment service is currently unavailable", "payment-service"))
        )
    }
}
