package com.booking.service.messaging.listener;

import com.booking.service.config.RabbitMqProperties;
import com.booking.service.messaging.contracts.BookingJobConfirmed;
import com.booking.service.messaging.contracts.BookingJobDenied;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class BookingEventListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("932fa05c-213f-49bb-a193-621ccf8c2f32");
    private static final UUID REQUEST_ID = UUID.fromString("2a1c5269-a265-409b-99a3-bf485373b435");

    @Mock
    private BookingService bookingService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RabbitMqProperties properties = new RabbitMqProperties();
    private BookingEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BookingEventListener(bookingService, objectMapper, properties);
    }

    @Test
    void forwardsConfirmedEventAndRequestIds() throws Exception {
        BookingJobConfirmed event = new BookingJobConfirmed(EVENT_ID, REQUEST_ID);

        listener.bookingEvents().accept(message(event, properties.getMessageTypes().getBookingJobConfirmed()));

        verify(bookingService).handleBookingJobConfirmed(EVENT_ID, REQUEST_ID);
        verifyNoMoreInteractions(bookingService);
    }

    @Test
    void forwardsDeniedEventAndRequestIds() throws Exception {
        BookingJobDenied event = new BookingJobDenied(EVENT_ID, REQUEST_ID);

        listener.bookingEvents().accept(message(event, properties.getMessageTypes().getBookingJobDenied()));

        verify(bookingService).handleBookingJobDenied(EVENT_ID, REQUEST_ID);
        verifyNoMoreInteractions(bookingService);
    }

    @Test
    void forwardsCancelErrorEventAndRequestIds() throws Exception {
        CancelBookingJobByRequestIdRequest event = new CancelBookingJobByRequestIdRequest(EVENT_ID, REQUEST_ID);

        listener.cancelBookingErrors().accept(message(event, properties.getMessageTypes().getCancelBookingJob()));

        verify(bookingService).handleError(EVENT_ID, REQUEST_ID);
        verifyNoMoreInteractions(bookingService);
    }

    @Test
    void propagatesCancelErrorFailureToAllowRedelivery() throws Exception {
        IllegalStateException failure = new IllegalStateException("Database unavailable");
        doThrow(failure).when(bookingService).handleError(EVENT_ID, REQUEST_ID);
        CancelBookingJobByRequestIdRequest event = new CancelBookingJobByRequestIdRequest(EVENT_ID, REQUEST_ID);
        Message<String> message = message(event, properties.getMessageTypes().getCancelBookingJob());

        assertThatThrownBy(() -> listener.cancelBookingErrors().accept(message))
                .isInstanceOf(RuntimeException.class)
                .hasCause(failure);

        verify(bookingService).handleError(EVENT_ID, REQUEST_ID);
    }

    private Message<String> message(Object payload, String messageType) throws Exception {
        return MessageBuilder.withPayload(objectMapper.writeValueAsString(payload))
                .setHeader(RabbitMqProperties.HEADER_MSG_TYPE, messageType)
                .build();
    }
}
