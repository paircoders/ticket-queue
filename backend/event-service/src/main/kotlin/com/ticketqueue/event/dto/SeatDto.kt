package com.ticketqueue.event.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
import java.math.BigDecimal
import java.util.UUID

/**
 * 좌석(Seat) API 요청/응답 DTO 모음
 *
 * - [SeatInfo]          : 개별 좌석 정보 (id, seatNumber, grade, status)
 * - [GradeGroup]        : 등급별 좌석 그룹 (grade, price, seats[])
 * - [SeatsResponse]     : 공개 API 응답 - 등급별 그룹핑 (scheduleId, grades[])
 * - [SoldSeatsResponse] : 내부 API 응답 - SOLD 좌석 ID 목록 (scheduleId, soldSeatIds[])
 */
class SeatDto {

    /** 개별 좌석 정보 */
    data class SeatInfo @JsonCreator constructor(
        @JsonProperty("id") val id: UUID,
        @JsonProperty("seatNumber") val seatNumber: String,
        @JsonProperty("grade") val grade: SeatGrade,
        @JsonProperty("status") val status: SeatStatus
    ) {
        companion object {
            fun from(seat: Seat): SeatInfo = SeatInfo(
                id = seat.id!!,
                seatNumber = seat.seatNumber,
                grade = seat.grade,
                status = seat.status
            )
        }
    }

    /** 등급별 좌석 그룹 */
    data class GradeGroup @JsonCreator constructor(
        @JsonProperty("grade") val grade: SeatGrade,
        @JsonProperty("price") val price: BigDecimal,
        @JsonProperty("seats") val seats: List<SeatInfo>
    )

    /** 공개 API 응답 - 회차별 등급 그룹핑 (REQ-EVT-006) */
    data class SeatsResponse(
        val scheduleId: UUID,
        val grades: List<GradeGroup>
    )

    /** 내부 API 응답 - Reservation Service가 SOLD 좌석 조회 시 사용 */
    data class SoldSeatsResponse(
        val scheduleId: UUID,
        val soldSeatIds: List<UUID>,
        val totalSeats: Long
    )

    /** 내부 API 응답 - Reservation Service가 좌석 선점 시 스냅샷 저장용으로 사용 */
    data class SeatDetailsResponse(
        val scheduleId: UUID,
        val eventId: UUID,
        val seats: List<SeatDetail>
    ) {
        data class SeatDetail(
            val seatId: UUID,
            val seatNumber: String,
            val grade: String,
            val price: BigDecimal
        )
    }
}
