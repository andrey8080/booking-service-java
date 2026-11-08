package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.dto.response.BookingHistoryResponse;
import com.booking.service.entity.BookingHistory;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.repository.BookingHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingHistoryServiceTest {

    @Mock
    private BookingHistoryRepository repository;

    @Mock
    private CurrentDateTimeProvider dateTimeProvider;

    private BookingHistoryService service;

    @BeforeEach
    void setUp() {
        service = new BookingHistoryService(repository, dateTimeProvider);
    }

    @Test
    void recordsCreationWithUserAndTimeFromProvider() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-04T13:25:47Z");
        when(dateTimeProvider.utcNow()).thenReturn(now);

        service.record(15L, null, BookingStatus.AWAIT_CONFIRMATION, "Создание", "42");

        ArgumentCaptor<BookingHistory> captor = ArgumentCaptor.forClass(BookingHistory.class);
        verify(repository).save(captor.capture());
        BookingHistory history = captor.getValue();
        assertThat(history.getBookingId()).isEqualTo(15L);
        assertThat(history.getPreviousStatus()).isNull();
        assertThat(history.getNewStatus()).isEqualTo(BookingStatus.AWAIT_CONFIRMATION);
        assertThat(history.getChangedAt()).isEqualTo(now);
        assertThat(history.getReason()).isEqualTo("Создание");
        assertThat(history.getInitiator()).isEqualTo("42");
    }

    @Test
    void recordsAutomaticTransitionAndPropagatesPersistenceFailure() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-04T15:35:27Z");
        when(dateTimeProvider.utcNow()).thenReturn(now);
        IllegalStateException failure = new IllegalStateException("Database unavailable");
        when(repository.save(any(BookingHistory.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.record(15L, BookingStatus.AWAIT_CONFIRMATION,
                BookingStatus.CONFIRMED, "Подтверждение каталога", "System"))
                .isSameAs(failure);

        ArgumentCaptor<BookingHistory> captor = ArgumentCaptor.forClass(BookingHistory.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPreviousStatus()).isEqualTo(BookingStatus.AWAIT_CONFIRMATION);
        assertThat(captor.getValue().getNewStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(captor.getValue().getInitiator()).isEqualTo("System");
    }

    @Test
    void retrievesRequestedPageWithDeterministicNewestFirstOrder() {
        PageRequest expectedPage = PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "changedAt", "id"));
        BookingHistory history = new BookingHistory(15L, BookingStatus.AWAIT_CONFIRMATION,
                BookingStatus.CONFIRMED, OffsetDateTime.parse("2026-11-04T13:25:47Z"),
                "Подтверждение каталога", "System");
        when(repository.findByBookingId(15L, expectedPage))
                .thenReturn(new PageImpl<>(List.of(history), expectedPage, 11));

        Page<BookingHistoryResponse> result = service.getHistory(15L, 2, 5);

        verify(repository).findByBookingId(15L, expectedPage);
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(5);
        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getContent()).containsExactly(BookingHistoryResponse.from(history));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1"})
    void rejectsInvalidPagination(int page, int size) {
        assertThatThrownBy(() -> service.getHistory(15L, page, size))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(repository);
    }
}
