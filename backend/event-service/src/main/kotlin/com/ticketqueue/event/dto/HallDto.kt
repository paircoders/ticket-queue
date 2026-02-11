package com.ticketqueue.event.dto

import com.ticketqueue.event.entity.Hall
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * 홀(Hall) API 요청/응답 DTO 모음
 *
 * - [CreateRequest] : POST 요청 - 홀 생성, seatTemplate을 DTO로 수신하여 Bean Validation 후 JSON 변환
 * - [UpdateRequest] : PATCH 요청 - 모든 필드 nullable (부분 수정 지원)
 * - [Response] : 목록 조회 응답 - seatTemplate 제외 (목록에서는 불필요)
 * - [DetailResponse] : 상세 조회 응답 - seatTemplate 포함 (JSONB -> DTO 역직렬화)
 * - [UpdateResponse] : 수정 응답 - updatedAt 포함
 * - [DeleteResponse] : 삭제 응답 - 결과 메시지
 */
class HallDto {

    /**
     * 홀 생성 요청
     *
     * seatTemplate은 SeatTemplateDto 타입으로 수신하여 @Valid를 통해
     * 중첩 검증(rows, seatsPerRow, gradeMapping)을 수행한 뒤,
     * HallService에서 ObjectMapper로 JSON 문자열 변환하여 DB에 저장한다.
     */
    data class CreateRequest(
        @field:NotBlank(message = "홀 이름은 필수입니다.")
        val name: String,

        @field:Min(value = 1, message = "수용 인원은 1 이상이어야 합니다.")
        val capacity: Int,

        @field:Valid
        @field:NotNull(message = "좌석 템플릿은 필수입니다.")
        val seatTemplate: SeatTemplateDto
    )

    /** 홀 수정 요청 - 모든 필드 nullable (PATCH 부분 수정, null 필드는 기존 값 유지) */
    data class UpdateRequest(
        @field:Size(min = 1, message = "홀 이름은 비어있을 수 없습니다.")
        val name: String? = null,

        @field:Min(value = 1, message = "수용 인원은 1 이상이어야 합니다.")
        val capacity: Int? = null,

        @field:Valid
        val seatTemplate: SeatTemplateDto? = null
    )

    /** 홀 목록 조회 응답 - seatTemplate 제외 (목록에서는 경량 응답) */
    data class Response(
        val id: UUID,
        val venueId: UUID,
        val name: String,
        val capacity: Int,
        val createdAt: LocalDateTime
    ) {
        companion object {
            fun from(hall: Hall): Response = Response(
                id = hall.id!!,
                venueId = hall.venue.id!!,
                name = hall.name,
                capacity = hall.capacity,
                createdAt = hall.createdAt!!
            )
        }
    }

    /** 홀 상세 조회 응답 - seatTemplate(JSONB -> DTO 역직렬화), updatedAt 포함 */
    data class DetailResponse(
        val id: UUID,
        val venueId: UUID,
        val name: String,
        val capacity: Int,
        val seatTemplate: SeatTemplateDto,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(hall: Hall, seatTemplate: SeatTemplateDto): DetailResponse = DetailResponse(
                id = hall.id!!,
                venueId = hall.venue.id!!,
                name = hall.name,
                capacity = hall.capacity,
                seatTemplate = seatTemplate,
                createdAt = hall.createdAt!!,
                updatedAt = hall.updatedAt!!
            )
        }
    }

    /** 홀 수정 응답 - 수정된 정보와 updatedAt 포함 */
    data class UpdateResponse(
        val id: UUID,
        val name: String,
        val capacity: Int,
        val updatedAt: LocalDateTime
    ) {
        companion object {
            fun from(hall: Hall): UpdateResponse = UpdateResponse(
                id = hall.id!!,
                name = hall.name,
                capacity = hall.capacity,
                updatedAt = hall.updatedAt!!
            )
        }
    }

    /** 홀 삭제 응답 */
    data class DeleteResponse(
        val message: String
    )
}
