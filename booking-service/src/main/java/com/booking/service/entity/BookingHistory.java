package com.booking.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "booking_history")
@Getter
@NoArgsConstructor
public class BookingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private BookingStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false)
    private BookingStatus newStatus;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String initiator;

    public BookingHistory(Long bookingId, BookingStatus previousStatus, BookingStatus newStatus,
                          OffsetDateTime changedAt, String reason, String initiator) {
        this.bookingId = bookingId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
        this.reason = reason;
        this.initiator = initiator;
    }
}
