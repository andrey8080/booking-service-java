package com.booking.service.entity;

import com.booking.service.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T12:34:56Z");

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"AWAIT_CONFIRMATION", "CONFIRMED"})
    void beginsCancellationAndPreservesPreviousStatus(BookingStatus originalStatus) {
        Booking booking = bookingIn(originalStatus);

        booking.beginCancellation(NOW);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        assertThat(booking.getPreviousStatus()).isEqualTo(originalStatus);
        assertThat(booking.getCancellationRequestedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"AWAIT_CONFIRMATION", "CONFIRMED"})
    void rollsBackToPreviousStatusAndClearsCancellation(BookingStatus originalStatus) {
        Booking booking = bookingIn(originalStatus);
        booking.beginCancellation(NOW);

        booking.rollbackCancellation();

        assertThat(booking.getStatus()).isEqualTo(originalStatus);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
    }

    @Test
    void completesCancellationAndClearsCancellation() {
        Booking booking = bookingIn(BookingStatus.CONFIRMED);
        booking.beginCancellation(NOW);

        booking.completeCancellation();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"NONE", "CANCELLED", "CANCELLATION_PENDING"})
    void rejectsCancellationFromInvalidStatus(BookingStatus status) {
        Booking booking = bookingIn(status);

        assertThatThrownBy(() -> booking.beginCancellation(NOW)).isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(status);
    }

    @Test
    void rejectsCancellationOfStartedConfirmedBooking() {
        Booking booking = bookingIn(BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> booking.beginCancellation(NOW.plusDays(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
    }

    @Test
    void rejectsCancellationWithoutCommandTime() {
        Booking booking = bookingIn(BookingStatus.AWAIT_CONFIRMATION);

        assertThatThrownBy(() -> booking.beginCancellation(null)).isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAIT_CONFIRMATION);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLATION_PENDING")
    void rejectsCompletionAndRollbackWithoutPendingCancellation(BookingStatus status) {
        Booking booking = bookingIn(status);

        assertThatThrownBy(booking::completeCancellation).isInstanceOf(BusinessException.class);
        assertThatThrownBy(booking::rollbackCancellation).isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(status);
    }

    @Test
    void rejectsRollbackWithoutValidPreviousStatus() {
        Booking booking = bookingIn(BookingStatus.CANCELLATION_PENDING);

        assertThatThrownBy(booking::rollbackCancellation).isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
    }

    @Test
    void deniesBookingAwaitingConfirmation() {
        Booking booking = bookingIn(BookingStatus.AWAIT_CONFIRMATION);

        booking.deny();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "AWAIT_CONFIRMATION")
    void rejectsDenialFromOtherStatuses(BookingStatus status) {
        Booking booking = bookingIn(status);

        assertThatThrownBy(booking::deny).isInstanceOf(BusinessException.class);
        assertThat(booking.getStatus()).isEqualTo(status);
    }

    private Booking bookingIn(BookingStatus status) {
        Booking booking = Booking.create(1L, 2L, NOW.toLocalDate().plusDays(1),
                NOW.toLocalDate().plusDays(3), NOW);
        ReflectionTestUtils.setField(booking, "status", status);
        return booking;
    }
}
