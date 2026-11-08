package com.booking.service.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookingConfirmationRaceTest {

    @Test
    void catalogConfirmationWinsOverPendingCancellation() {
        var now = OffsetDateTime.parse("2026-11-01T14:23:17Z");
        var booking = Booking.create(1L, 2L, now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(2), now);
        booking.beginCancellation(now);

        booking.confirm();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
    }
}
