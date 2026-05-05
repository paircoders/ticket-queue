package com.ticketqueue.queue.client

import com.ticketqueue.common.config.InternalFeignConfig
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@FeignClient(
    name = "event-service",
    url = "\${spring.cloud.openfeign.client.config.event-service.url}",
    configuration = [InternalFeignConfig::class]
)
interface EventServiceClient {

    @GetMapping("/internal/schedules/{scheduleId}/sellable")
    fun checkSellable(@PathVariable scheduleId: UUID): SellableResponse

    @GetMapping("/internal/schedules/ended")
    fun getEndedScheduleIds(): EndedScheduleIdsResponse

    data class SellableResponse(val sellable: Boolean, val reason: String?)

    data class EndedScheduleIdsResponse(val scheduleIds: List<UUID>)
}
