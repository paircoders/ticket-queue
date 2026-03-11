package com.ticketqueue.queue.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@FeignClient(name = "event-service", url = "\${feign.client.config.event-service.url}")
interface EventServiceClient {

    @GetMapping("/internal/schedules/{scheduleId}/sellable")
    fun checkSellable(@PathVariable scheduleId: UUID): SellableResponse

    data class SellableResponse(val sellable: Boolean, val reason: String?)
}
