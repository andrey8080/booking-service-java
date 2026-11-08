package com.booking.service.service;

import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;

@Service
@RequiredArgsConstructor
public class BookingStatisticsService {

    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public BookingStatisticsResponse getStatistics(LocalDate dateFrom, LocalDate dateTo) {
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo не может быть раньше dateFrom");
        }

        var from = dateFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
        var toExclusive = dateTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        var byStatus = new EnumMap<BookingStatus, Long>(BookingStatus.class);
        for (BookingStatus status : BookingStatus.values()) {
            byStatus.put(status, 0L);
        }
        bookingRepository.countByStatus(from, toExclusive)
                .forEach(count -> byStatus.put(count.getStatus(), count.getBookingCount()));

        return new BookingStatisticsResponse(
                bookingRepository.countInPeriod(from, toExclusive),
                byStatus,
                bookingRepository.findTopResources(from, toExclusive, PageRequest.of(0, 5))
        );
    }
}
