package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import com.booking.service.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T12:34:56Z");
    private static final UUID REQUEST_ID = UUID.fromString("40323432-6ec5-4011-b840-bf7085170209");

    @Mock
    private BookingRepository repository;

    @Mock
    private BookingEventPublisher publisher;

    @Mock
    private CurrentDateTimeProvider dateTimeProvider;

    @Mock
    private BookingHistoryService historyService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private BookingService service;

    @BeforeEach
    void setUp() {
        lenient().when(processedEventRepository.register(any()))
                .thenReturn(true);
        service = new BookingService(repository, publisher, dateTimeProvider, historyService, processedEventRepository);
    }

    @Test
    void startsCancellationAndPublishesCatalogCommand() {
        Booking booking = booking();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));
        when(dateTimeProvider.utcNow()).thenReturn(NOW);

        service.cancelBooking(1L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        assertThat(booking.getPreviousStatus()).isEqualTo(BookingStatus.AWAIT_CONFIRMATION);
        assertThat(booking.getCancellationRequestedAt()).isEqualTo(NOW);
        verify(repository).save(booking);
        ArgumentCaptor<CancelBookingJobByRequestIdRequest> captor =
                ArgumentCaptor.forClass(CancelBookingJobByRequestIdRequest.class);
        verify(publisher).publishCancelBookingJob(captor.capture());
        assertThat(captor.getValue().getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(captor.getValue().getRequestId()).isNotEqualTo(captor.getValue().getEventId());
    }

    @Test
    void completesCancellationLocallyWithoutCatalogRequest() {
        Booking booking = Booking.create(1L, 2L, NOW.toLocalDate().plusDays(1),
                NOW.toLocalDate().plusDays(3), NOW);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));
        when(dateTimeProvider.utcNow()).thenReturn(NOW);

        service.cancelBooking(1L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(repository).save(booking);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"AWAIT_CONFIRMATION", "CONFIRMED"})
    void rollsBackCancellationWhenCommandFails(BookingStatus originalStatus) {
        Booking booking = booking();
        if (originalStatus == BookingStatus.CONFIRMED) {
            booking.confirm();
        }
        booking.beginCancellation(NOW);
        when(repository.findByCatalogRequestId(REQUEST_ID)).thenReturn(Optional.of(booking));

        service.handleError(UUID.randomUUID(), REQUEST_ID);

        assertThat(booking.getStatus()).isEqualTo(originalStatus);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
        verify(repository).save(booking);
        verifyNoInteractions(publisher);
    }

    @Test
    void completesPendingCancellationOnCatalogDenial() {
        Booking booking = booking();
        booking.beginCancellation(NOW);
        when(repository.findByCatalogRequestId(REQUEST_ID)).thenReturn(Optional.of(booking));

        service.handleBookingJobDenied(UUID.randomUUID(), REQUEST_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
        verify(repository).save(booking);
        verifyNoInteractions(publisher);
    }

    @Test
    void deniesInitialBookingWithoutStartingCancellation() {
        Booking booking = booking();
        when(repository.findByCatalogRequestId(REQUEST_ID)).thenReturn(Optional.of(booking));

        service.handleBookingJobDenied(UUID.randomUUID(), REQUEST_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
        verify(repository).save(booking);
        verifyNoInteractions(publisher, dateTimeProvider);
    }

    @Test
    void ignoresUnknownRequestInErrorAndDenialHandlers() {
        when(repository.findByCatalogRequestId(REQUEST_ID)).thenReturn(Optional.empty());

        service.handleError(UUID.randomUUID(), REQUEST_ID);
        service.handleBookingJobDenied(UUID.randomUUID(), REQUEST_ID);

        verifyNoInteractions(publisher);
    }

    @Test
    void ignoresLateCancellationErrorAfterConfirmation() {
        Booking booking = booking();
        booking.confirm();
        when(repository.findByCatalogRequestId(REQUEST_ID)).thenReturn(Optional.of(booking));

        service.handleError(UUID.randomUUID(), REQUEST_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(repository, never()).save(booking);
    }

    @Test
    void doesNotSendAnotherCommandForPendingCancellation() {
        Booking booking = booking();
        booking.beginCancellation(NOW);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));
        when(dateTimeProvider.utcNow()).thenReturn(NOW.plusMinutes(1));

        assertThatThrownBy(() -> service.cancelBooking(1L)).isInstanceOf(BusinessException.class);

        assertThat(booking.getCancellationRequestedAt()).isEqualTo(NOW);
        verifyNoInteractions(publisher);
        verify(repository, never()).save(booking);
    }

    private Booking booking() {
        Booking booking = Booking.create(1L, 2L, NOW.toLocalDate().plusDays(1),
                NOW.toLocalDate().plusDays(3), NOW);
        booking.setCatalogRequestId(REQUEST_ID);
        return booking;
    }
}
