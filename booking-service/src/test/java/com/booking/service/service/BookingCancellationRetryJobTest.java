package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCancellationRetryJobTest {

    @Mock
    private BookingRepository repository;
    @Mock
    private BookingEventPublisher publisher;
    @Mock
    private CurrentDateTimeProvider clock;

    @Test
    void retriesExpiredCancellationsAndContinuesAfterPublisherFailure() {
        var now = OffsetDateTime.parse("2026-11-01T14:23:17Z");
        var first = pendingBooking(now, UUID.randomUUID());
        var second = pendingBooking(now, UUID.randomUUID());
        when(clock.utcNow()).thenReturn(now);
        when(repository.findByStatusAndCancellationRequestedAtBefore(
                BookingStatus.CANCELLATION_PENDING, now.minusMinutes(7))).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker unavailable")).doNothing()
                .when(publisher).publishCancelBookingJob(any());

        new BookingCancellationRetryJob(repository, publisher, clock, "PT7M").retryPendingCancellations();

        var commands = ArgumentCaptor.forClass(
                com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest.class);
        verify(publisher, times(2)).publishCancelBookingJob(commands.capture());
        assertThat(commands.getAllValues()).extracting(command -> command.getRequestId())
                .containsExactly(first.getCatalogRequestId(), second.getCatalogRequestId());
        assertThat(first.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        assertThat(second.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        verify(repository, never()).save(any());
    }

    private Booking pendingBooking(OffsetDateTime now, UUID requestId) {
        var booking = Booking.create(1L, 2L, now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(2), now);
        booking.setCatalogRequestId(requestId);
        booking.beginCancellation(now.minusMinutes(10));
        return booking;
    }
}
