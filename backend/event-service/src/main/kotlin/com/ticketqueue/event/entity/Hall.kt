package com.ticketqueue.event.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

/**
 * 홀(Hall) 엔티티
 *
 * 공연장(Venue) 내부의 개별 공연 공간을 나타낸다.
 * 각 홀은 고유한 좌석 배치 정보(seatTemplate)를 JSONB 형태로 저장하며,
 * 이 템플릿은 공연(Event) 생성 시 실제 좌석 데이터의 원본이 된다.
 *
 * @see Venue
 * @see SeatTemplateDto
 */
@Entity
@Table(name = "halls", schema = "event_service")
class Hall(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    val venue: Venue,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var capacity: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seat_template", columnDefinition = "jsonb", nullable = false)
    var seatTemplate: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {
    /**
     * PATCH 요청을 위한 부분 업데이트
     *
     * null이 아닌 필드만 선택적으로 변경한다.
     * seatTemplate은 이미 JSON 문자열로 직렬화된 상태로 전달받는다.
     *
     * @param name 변경할 홀 이름 (null이면 기존 값 유지)
     * @param capacity 변경할 수용 인원 (null이면 기존 값 유지)
     * @param seatTemplate 변경할 좌석 템플릿 JSON 문자열 (null이면 기존 값 유지)
     */
    fun update(name: String?, capacity: Int?, seatTemplate: String?) {
        name?.let { this.name = it }
        capacity?.let { this.capacity = it }
        seatTemplate?.let { this.seatTemplate = it }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hall) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
