package com.ticketqueue.event.dto

import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Venue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연장(Venue) API 요청/응답 DTO 모음
 *
 * - [CreateRequest] : POST 요청 - 공연장 생성 시 필수 필드 검증
 * - [UpdateRequest] : PATCH 요청 - 모든 필드가 nullable (부분 수정 지원)
 * - [Response] : 목록 조회 응답 - 기본 공연장 정보
 * - [DetailResponse] : 상세 조회 응답 - 소속 홀 목록 포함
 * - [UpdateResponse] : 수정 응답 - updatedAt 포함
 * - [HallSummary] : 상세 조회 시 홀 요약 정보 (id, name, capacity만)
 * - [DeleteResponse] : 삭제 응답 - 결과 메시지
 */
class VenueDto {

    /** 공연장 생성 요청 - 모든 필드 필수 (@NotBlank 검증) */
    data class CreateRequest(
        @field:NotBlank(message = "공연장 이름은 필수입니다.")
        val name: String,

        @field:NotBlank(message = "주소는 필수입니다.")
        val address: String,

        @field:NotBlank(message = "도시는 필수입니다.")
        val city: String
    )

    /** 공연장 수정 요청 - 모든 필드 nullable (PATCH 부분 수정, null 필드는 기존 값 유지) */
    data class UpdateRequest(
        @field:Size(min = 1, message = "공연장 이름은 비어있을 수 없습니다.")
        val name: String? = null,

        @field:Size(min = 1, message = "주소는 비어있을 수 없습니다.")
        val address: String? = null,

        @field:Size(min = 1, message = "도시는 비어있을 수 없습니다.")
        val city: String? = null
    )

    /** 공연장 목록 조회 응답 - 기본 정보만 포함 */
    data class Response(
        val id: UUID,
        val name: String,
        val address: String,
        val city: String,
        val createdAt: LocalDateTime
    ) {
        companion object {
            fun from(venue: Venue): Response = Response(
                id = venue.id!!,
                name = venue.name,
                address = venue.address,
                city = venue.city,
                createdAt = venue.createdAt!!
            )
        }
    }

    /** 홀 요약 정보 - 공연장 상세 조회 시 소속 홀의 요약 정보 */
    data class HallSummary(
        val id: UUID,
        val name: String,
        val capacity: Int
    ) {
        companion object {
            fun from(hall: Hall): HallSummary = HallSummary(
                id = hall.id!!,
                name = hall.name,
                capacity = hall.capacity
            )
        }
    }

    /** 공연장 상세 조회 응답 - 소속 홀 목록(HallSummary), updatedAt 포함 */
    data class DetailResponse(
        val id: UUID,
        val name: String,
        val address: String,
        val city: String,
        val halls: List<HallSummary>,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(venue: Venue, halls: List<Hall>): DetailResponse = DetailResponse(
                id = venue.id!!,
                name = venue.name,
                address = venue.address,
                city = venue.city,
                halls = halls.map { HallSummary.from(it) },
                createdAt = venue.createdAt!!,
                updatedAt = venue.updatedAt!!
            )
        }
    }

    /** 공연장 수정 응답 - 수정된 정보와 updatedAt 포함 */
    data class UpdateResponse(
        val id: UUID,
        val name: String,
        val address: String,
        val city: String,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(venue: Venue): UpdateResponse = UpdateResponse(
                id = venue.id!!,
                name = venue.name,
                address = venue.address,
                city = venue.city,
                updatedAt = venue.updatedAt!!
            )
        }
    }

    /** 공연장 삭제 응답 */
    data class DeleteResponse(
        val message: String
    )
}
