package com.ticketqueue.event.dto

import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.SeatGrade
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연(Event) API 요청/응답 DTO 모음
 *
 * - [CreateRequest]      : POST 요청 - 공연/회차/좌석 일괄 생성
 * - [ScheduleRequest]    : 회차 생성 정보
 * - [CreateResponse]     : 생성 응답 - 핵심 메타정보
 * - [ListResponse]       : 목록 조회 응답 - QueryDSL 프로젝션용 (Venue JOIN + Schedule 집계)
 * - [ScheduleTimeInfo]   : 회차별 상세 정보 (isSoldOut 포함)
 * - [ScheduleDateGroup]  : 날짜별 회차 그룹
 * - [DetailResponse]     : 상세 조회 응답 - 회차 날짜별 그룹핑
 * - [UpdateRequest]      : PATCH 요청 - nullable 필드 (판매 후 artist 수정 불가)
 * - [UpdateResponse]     : 수정 응답
 * - [DeleteResponse]     : 삭제 응답
 */
class EventDto {

    /** 공연 생성 요청 - 회차 및 등급별 가격 포함 일괄 생성 */
    data class CreateRequest(
        @field:NotBlank(message = "공연 제목은 필수입니다.")
        val title: String,

        @field:NotBlank(message = "아티스트는 필수입니다.")
        val artist: String,

        val description: String? = null,

        @field:NotNull(message = "공연장 ID는 필수입니다.")
        val venueId: UUID,

        @field:NotNull(message = "홀 ID는 필수입니다.")
        val hallId: UUID,

        @field:NotEmpty(message = "등급별 가격 정보는 필수입니다.")
        val priceByGrade: Map<SeatGrade, @DecimalMin(value = "0", message = "가격은 0 이상이어야 합니다.") BigDecimal>,

        @field:NotEmpty(message = "회차 정보는 필수입니다.")
        @field:Size(max = 50, message = "회차는 최대 50개까지 등록 가능합니다.")
        @field:Valid
        val schedules: List<ScheduleRequest>
    )

    /** 회차 생성 요청 */
    data class ScheduleRequest(
        @field:Min(value = 1, message = "회차 순번은 1 이상이어야 합니다.")
        val playSequence: Int,

        @field:NotNull(message = "공연 시작 일시는 필수입니다.")
        val eventStartAt: LocalDateTime,

        @field:NotNull(message = "공연 종료 일시는 필수입니다.")
        val eventEndAt: LocalDateTime,

        @field:NotNull(message = "판매 시작 일시는 필수입니다.")
        val saleStartAt: LocalDateTime,

        @field:NotNull(message = "판매 종료 일시는 필수입니다.")
        val saleEndAt: LocalDateTime
    )

    /** 공연 생성 응답 */
    data class CreateResponse(
        val id: UUID,
        val title: String,
        val artist: String,
        val status: EventStatus,
        val createdAt: LocalDateTime
    ) {
        companion object {
            fun from(event: Event): CreateResponse = CreateResponse(
                id = event.id!!,
                title = event.title,
                artist = event.artist,
                status = event.status,
                createdAt = event.createdAt!!
            )
        }
    }

    /** 공연 목록 조회 응답 - QueryDSL Tuple 프로젝션으로 구성 */
    data class ListResponse(
        val id: UUID,
        val title: String,
        val artist: String,
        val venueName: String,
        val startDate: LocalDateTime?,
        val endDate: LocalDateTime?,
        val status: EventStatus
    )

    /** 회차별 상세 정보 - 좌석 매진 여부 포함 */
    data class ScheduleTimeInfo(
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
            fun from(schedule: EventSchedule, isSoldOut: Boolean): ScheduleTimeInfo = ScheduleTimeInfo(
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

    /** 날짜별 회차 그룹 - 상세 조회 시 eventStartAt 기준으로 그룹핑 */
    data class ScheduleDateGroup(
        val date: LocalDate,
        val times: List<ScheduleTimeInfo>
    )

    /** 공연 상세 조회 응답 - 회차 날짜별 그룹핑 포함 */
    data class DetailResponse(
        val id: UUID,
        val title: String,
        val artist: String,
        val description: String?,
        val venueId: UUID,
        val venueName: String,
        val hallId: UUID,
        val hallName: String,
        val status: EventStatus,
        val schedules: List<ScheduleDateGroup>,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    )

    /** 공연 수정 요청 - nullable 필드 (판매 시작 후 artist 수정 불가) */
    data class UpdateRequest(
        @field:Size(min = 1, message = "공연 제목은 비어있을 수 없습니다.")
        val title: String? = null,

        @field:Size(min = 1, message = "아티스트는 비어있을 수 없습니다.")
        val artist: String? = null,

        val description: String? = null
    )

    /** 공연 수정 응답 */
    data class UpdateResponse(
        val id: UUID,
        val title: String,
        val artist: String,
        val description: String?,
        val status: EventStatus,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(event: Event): UpdateResponse = UpdateResponse(
                id = event.id!!,
                title = event.title,
                artist = event.artist,
                description = event.description,
                status = event.status,
                updatedAt = event.updatedAt!!
            )
        }
    }

    /** 공연 삭제 응답 */
    data class DeleteResponse(
        val message: String
    )
}
