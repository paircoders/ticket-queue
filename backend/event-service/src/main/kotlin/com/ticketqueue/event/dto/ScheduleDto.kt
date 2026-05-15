package com.ticketqueue.event.dto

import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.SeatGrade
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연 회차(EventSchedule) API 요청/응답 DTO 모음
 *
 * - [CreateRequest]        : POST 요청 - 회차 생성 및 좌석 초기화
 * - [CreateResponse]       : 생성 응답
 * - [ListResponse]         : 목록 조회 응답
 * - [DetailResponse]       : 상세 조회 응답
 * - [ChangeStatusRequest]  : PATCH 요청 - 상태 변경
 * - [ChangeStatusResponse] : 상태 변경 응답
 */
class ScheduleDto {

    /** 회차 생성 요청 - 좌석 초기화를 위해 등급별 가격 포함 */
    data class CreateRequest(
        @field:Min(value = 1, message = "회차 순번은 1 이상이어야 합니다.")
        val playSequence: Int,

        @field:NotNull(message = "공연 시작 일시는 필수입니다.")
        val eventStartAt: LocalDateTime,

        @field:NotNull(message = "공연 종료 일시는 필수입니다.")
        val eventEndAt: LocalDateTime,

        @field:NotNull(message = "판매 시작 일시는 필수입니다.")
        val saleStartAt: LocalDateTime,

        @field:NotNull(message = "판매 종료 일시는 필수입니다.")
        val saleEndAt: LocalDateTime,

        @field:NotEmpty(message = "등급별 가격 정보는 필수입니다.")
        val priceByGrade: Map<SeatGrade, @DecimalMin(value = "0", message = "가격은 0 이상이어야 합니다.") BigDecimal>
    )

    /** 회차 생성 응답 */
    data class CreateResponse(
        val id: UUID,
        val eventId: UUID,
        val playSequence: Int,
        val eventStartAt: LocalDateTime,
        val eventEndAt: LocalDateTime,
        val saleStartAt: LocalDateTime,
        val saleEndAt: LocalDateTime,
        val status: ScheduleStatus,
        val createdAt: LocalDateTime
    ) {
        companion object {
            fun from(schedule: EventSchedule): CreateResponse = CreateResponse(
                id = schedule.id!!,
                eventId = schedule.event.id!!,
                playSequence = schedule.playSequence,
                eventStartAt = schedule.eventStartAt,
                eventEndAt = schedule.eventEndAt,
                saleStartAt = schedule.saleStartAt,
                saleEndAt = schedule.saleEndAt,
                status = schedule.status,
                createdAt = schedule.createdAt!!
            )
        }
    }

    /** 회차 목록 조회 응답 */
    data class ListResponse(
        val id: UUID,
        val playSequence: Int,
        val eventStartAt: LocalDateTime,
        val eventEndAt: LocalDateTime,
        val saleStartAt: LocalDateTime,
        val saleEndAt: LocalDateTime,
        val status: ScheduleStatus,
        val isSoldOut: Boolean
    ) {
        companion object {
            fun from(schedule: EventSchedule, isSoldOut: Boolean): ListResponse = ListResponse(
                id = schedule.id!!,
                playSequence = schedule.playSequence,
                eventStartAt = schedule.eventStartAt,
                eventEndAt = schedule.eventEndAt,
                saleStartAt = schedule.saleStartAt,
                saleEndAt = schedule.saleEndAt,
                status = schedule.status,
                isSoldOut = isSoldOut
            )
        }
    }

    /** 회차 상세 조회 응답 */
    data class DetailResponse(
        val id: UUID,
        val eventId: UUID,
        val eventTitle: String,
        val playSequence: Int,
        val eventStartAt: LocalDateTime,
        val eventEndAt: LocalDateTime,
        val saleStartAt: LocalDateTime,
        val saleEndAt: LocalDateTime,
        val status: ScheduleStatus,
        val isSoldOut: Boolean,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(schedule: EventSchedule, isSoldOut: Boolean): DetailResponse = DetailResponse(
                id = schedule.id!!,
                eventId = schedule.event.id!!,
                eventTitle = schedule.event.title,
                playSequence = schedule.playSequence,
                eventStartAt = schedule.eventStartAt,
                eventEndAt = schedule.eventEndAt,
                saleStartAt = schedule.saleStartAt,
                saleEndAt = schedule.saleEndAt,
                status = schedule.status,
                isSoldOut = isSoldOut,
                createdAt = schedule.createdAt!!,
                updatedAt = schedule.updatedAt!!
            )
        }
    }

    /** 회차 상태 변경 요청 */
    data class ChangeStatusRequest(
        @field:NotNull(message = "변경할 상태는 필수입니다.")
        val status: ScheduleStatus
    )

    /** 회차 상태 변경 응답 */
    data class ChangeStatusResponse(
        val id: UUID,
        val previousStatus: ScheduleStatus,
        val currentStatus: ScheduleStatus,
        val updatedAt: LocalDateTime
    )

    /** 회차 판매 가능 여부 응답 (내부 API용 — Queue Service에서 대기열 진입 전 검증) */
    data class SellableResponse(
        val sellable: Boolean,
        val reason: String? = null
    )

    /** 내부 서비스용 회차 핵심 정보 — 취소 검증, 환불 기준일 등 공용 */
    data class ScheduleInfoResponse(
        val scheduleId: UUID,
        val eventId: UUID,
        val eventStartAt: LocalDateTime,
        val eventEndAt: LocalDateTime,
        val saleStartAt: LocalDateTime,
        val saleEndAt: LocalDateTime
    )

    /** 종료된 회차 ID 목록 응답 (내부 API용 — Queue Service 정리 배치 전용) */
    data class EndedScheduleIdsResponse(val scheduleIds: List<UUID>)
}
