package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.dto.response.BookingHistoryResponse;
import com.booking.service.entity.BookingHistory;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.repository.BookingHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingHistoryService {

    private final BookingHistoryRepository bookingHistoryRepository;
    private final CurrentDateTimeProvider dateTimeProvider;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long bookingId, BookingStatus previousStatus, BookingStatus newStatus,
                       String reason, String initiator) {
        bookingHistoryRepository.save(new BookingHistory(bookingId, previousStatus, newStatus,
                dateTimeProvider.utcNow(), reason, initiator));
    }

    @Transactional(readOnly = true)
    public Page<BookingHistoryResponse> getHistory(Long bookingId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException("Номер страницы должен быть неотрицательным, размер — положительным");
        }
        Sort sort = Sort.by(Sort.Direction.DESC, "changedAt", "id");
        return bookingHistoryRepository.findByBookingId(bookingId, PageRequest.of(page, size, sort))
                .map(BookingHistoryResponse::from);
    }
}
