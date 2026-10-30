package com.booking.service.service;

import com.booking.service.dto.response.ResourceBookingCount;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingStatisticsServiceTest {

    @Mock
    private BookingRepository repository;

    @InjectMocks
    private BookingStatisticsService service;

    @Test
    void includesBothDatesAndAllStatuses() {
        var from = OffsetDateTime.parse("2026-10-26T00:00:00Z");
        var toExclusive = OffsetDateTime.parse("2026-11-09T00:00:00Z");
        var count = mock(BookingRepository.StatusBookingCount.class);
        when(count.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(count.getBookingCount()).thenReturn(4L);
        when(repository.countByStatus(from, toExclusive)).thenReturn(List.of(count));
        when(repository.countInPeriod(from, toExclusive)).thenReturn(4L);
        when(repository.findTopResources(from, toExclusive, PageRequest.of(0, 5)))
                .thenReturn(List.of(new ResourceBookingCount(12L, 4)));

        var result = service.getStatistics(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 8));

        assertThat(result.totalBookings()).isEqualTo(4);
        assertThat(result.bookingsByStatus()).hasSize(BookingStatus.values().length);
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(result.bookingsByStatus()).containsEntry(status, status == BookingStatus.CONFIRMED ? 4L : 0L);
        }
        assertThat(result.topResources()).containsExactly(new ResourceBookingCount(12L, 4));
    }

    @Test
    void emptySingleDayReturnsZeroes() {
        var from = OffsetDateTime.parse("2026-11-08T00:00:00Z");
        var toExclusive = OffsetDateTime.parse("2026-11-09T00:00:00Z");
        when(repository.countByStatus(from, toExclusive)).thenReturn(List.of());
        when(repository.findTopResources(from, toExclusive, PageRequest.of(0, 5))).thenReturn(List.of());

        var result = service.getStatistics(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 8));

        assertThat(result.totalBookings()).isZero();
        assertThat(result.bookingsByStatus()).hasSize(BookingStatus.values().length);
        assertThat(result.bookingsByStatus().values()).containsOnly(0L);
        assertThat(result.topResources()).isEmpty();
        verify(repository).countInPeriod(from, toExclusive);
    }

    @Test
    void rejectsReversedPeriodBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.getStatistics(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 10, 26)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
}
