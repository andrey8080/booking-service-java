package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import com.booking.service.repository.ProcessedEventRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventIdempotencyTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingEventPublisher publisher;
    @Mock
    private CurrentDateTimeProvider clock;
    @Mock
    private BookingHistoryService historyService;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @InjectMocks
    private BookingService service;

    @ParameterizedTest
    @ValueSource(strings = {"confirmed", "denied", "error"})
    void skipsPreviouslyProcessedEventBeforeChangingBooking(String type) {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(processedEventRepository.register(eventId)).thenReturn(false);

        switch (type) {
            case "confirmed" -> service.handleBookingJobConfirmed(eventId, requestId);
            case "denied" -> service.handleBookingJobDenied(eventId, requestId);
            case "error" -> service.handleError(eventId, requestId);
            default -> throw new IllegalArgumentException(type);
        }

        verify(processedEventRepository).register(eventId);
        verifyNoInteractions(bookingRepository, publisher, clock, historyService);
    }
}
