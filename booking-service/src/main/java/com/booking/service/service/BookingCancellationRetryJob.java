package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.contracts.CancelBookingJobByRequestIdRequest;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@Slf4j
public class BookingCancellationRetryJob {

    private final BookingRepository bookingRepository;
    private final BookingEventPublisher bookingEventPublisher;
    private final CurrentDateTimeProvider dateTimeProvider;
    private final Duration timeout;

    public BookingCancellationRetryJob(BookingRepository bookingRepository,
                                       BookingEventPublisher bookingEventPublisher,
                                       CurrentDateTimeProvider dateTimeProvider,
                                       @Value("${booking.cancellation.timeout:PT5M}") String timeout) {
        this.bookingRepository = bookingRepository;
        this.bookingEventPublisher = bookingEventPublisher;
        this.dateTimeProvider = dateTimeProvider;
        this.timeout = Duration.parse(timeout);
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("Таймаут отмены должен быть положительным");
        }
    }

    @Scheduled(fixedDelayString = "${booking.cancellation.retry-delay:PT1M}")
    public void retryPendingCancellations() {
        var bookings = bookingRepository.findByStatusAndCancellationRequestedAtBefore(
                BookingStatus.CANCELLATION_PENDING, dateTimeProvider.utcNow().minus(timeout));
        for (var booking : bookings) {
            try {
                bookingEventPublisher.publishCancelBookingJob(new CancelBookingJobByRequestIdRequest(
                        UUID.randomUUID(), booking.getCatalogRequestId()));
            } catch (Exception e) {
                log.error("Не удалось повторно отправить отмену бронирования {}", booking.getId(), e);
            }
        }
    }
}
