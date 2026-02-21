package com.ticketqueue.event.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty

/**
 * 좌석 템플릿 DTO
 *
 * 홀(Hall)의 좌석 배치 구조를 정의하는 검증용 DTO이다.
 * 클라이언트에서 수신한 좌석 정보를 Bean Validation으로 검증한 뒤,
 * HallService에서 ObjectMapper를 통해 JSON 문자열로 직렬화하여 DB JSONB 컬럼에 저장한다.
 * 조회 시에는 JSONB 문자열을 다시 이 DTO로 역직렬화하여 응답한다.
 *
 * @property rows 좌석 행 식별자 목록 (예: ["A", "B", "C"])
 * @property seatsPerRow 각 행당 좌석 수
 * @property gradeMapping 행 -> 등급 매핑 (예: {"A": "VIP", "B": "R", "C": "S"})
 */
data class SeatTemplateDto(
    @field:NotEmpty(message = "좌석 행 목록은 비어있을 수 없습니다.")
    val rows: List<String>,

    @field:Min(value = 1, message = "행당 좌석 수는 1 이상이어야 합니다.")
    val seatsPerRow: Int,

    @field:NotEmpty(message = "등급 매핑은 비어있을 수 없습니다.")
    val gradeMapping: Map<String, String>
)
