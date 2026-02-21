package com.ticketqueue.event.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연장(Venue) 엔티티
 *
 * 콘서트, 뮤지컬 등의 공연이 열리는 물리적 장소를 나타낸다.
 * 하나의 공연장은 여러 홀(Hall)을 포함할 수 있으며,
 * 홀과는 1:N 관계로 CascadeType.PERSIST/MERGE를 통해 영속성을 전이한다.
 *
 * @see Hall
 */
@Entity
@Table(name = "venues", schema = "event_service")
class Venue(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var address: String,

    @Column(nullable = false)
    var city: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null,

    @OneToMany(mappedBy = "venue", cascade = [CascadeType.PERSIST, CascadeType.MERGE], fetch = FetchType.LAZY)
    val halls: MutableList<Hall> = mutableListOf()
) {
    /**
     * PATCH 요청을 위한 부분 업데이트
     *
     * null이 아닌 필드만 선택적으로 변경한다.
     * Kotlin의 let 스코프 함수를 활용하여 null 파라미터는 기존 값을 유지한다.
     *
     * @param name 변경할 공연장 이름 (null이면 기존 값 유지)
     * @param address 변경할 주소 (null이면 기존 값 유지)
     * @param city 변경할 도시 (null이면 기존 값 유지)
     */
    fun update(name: String?, address: String?, city: String?) {
        name?.let { this.name = it }
        address?.let { this.address = it }
        city?.let { this.city = it }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Venue) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
