package com.booking.service.repository;

import com.booking.service.dto.response.ResourceBookingCount;
import com.booking.service.entity.Booking;
import com.booking.service.entity.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class BookingStatisticsRepositoryTest {

    @Autowired
    private BookingRepository repository;

    @Test
    void aggregatesCreatedAtPeriodAndLimitsTopResourcesWithStableTies() {
        var from = OffsetDateTime.parse("2026-10-26T00:00:00Z");
        var toExclusive = OffsetDateTime.parse("2026-11-09T00:00:00Z");
        save(1L, from.minusNanos(1000), BookingStatus.CONFIRMED);
        save(1L, from, BookingStatus.CONFIRMED);
        save(1L, toExclusive.minusNanos(1000), BookingStatus.CONFIRMED);
        save(1L, toExclusive, BookingStatus.CONFIRMED);
        save(1L, OffsetDateTime.parse("2026-11-09T02:30:00+03:00"), BookingStatus.CANCELLED);
        for (long resourceId = 7; resourceId >= 2; resourceId--) {
            save(resourceId, from.plusDays(1), BookingStatus.AWAIT_CONFIRMATION);
        }
        repository.flush();

        assertThat(repository.countInPeriod(from, toExclusive)).isEqualTo(9);
        var byStatus = repository.countByStatus(from, toExclusive).stream()
                .collect(Collectors.toMap(BookingRepository.StatusBookingCount::getStatus,
                        BookingRepository.StatusBookingCount::getBookingCount));
        assertThat(byStatus).containsExactlyInAnyOrderEntriesOf(Map.of(
                BookingStatus.CONFIRMED, 2L,
                BookingStatus.CANCELLED, 1L,
                BookingStatus.AWAIT_CONFIRMATION, 6L
        ));
        assertThat(repository.findTopResources(from, toExclusive, PageRequest.of(0, 5)))
                .containsExactly(new ResourceBookingCount(1L, 3), new ResourceBookingCount(2L, 1),
                        new ResourceBookingCount(3L, 1), new ResourceBookingCount(4L, 1),
                        new ResourceBookingCount(5L, 1));
    }

    @Test
    void countsEveryBookingStatus() {
        var from = OffsetDateTime.parse("2026-10-26T00:00:00Z");
        for (BookingStatus status : BookingStatus.values()) {
            save(1L, from, status);
        }
        repository.flush();

        var counts = repository.countByStatus(from, from.plusDays(1));

        assertThat(counts).extracting(BookingRepository.StatusBookingCount::getStatus)
                .containsExactlyInAnyOrder(BookingStatus.values());
        assertThat(counts).extracting(BookingRepository.StatusBookingCount::getBookingCount).containsOnly(1L);
    }

    @Test
    void returnsEmptyAggregatesForPeriodWithoutBookings() {
        var from = OffsetDateTime.parse("2026-10-26T00:00:00Z");
        var toExclusive = from.plusDays(1);

        assertThat(repository.countInPeriod(from, toExclusive)).isZero();
        assertThat(repository.countByStatus(from, toExclusive)).isEmpty();
        assertThat(repository.findTopResources(from, toExclusive, PageRequest.of(0, 5))).isEmpty();
    }

    private void save(long resourceId, OffsetDateTime createdAt, BookingStatus status) {
        var booking = Booking.create(1L, resourceId, LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 2), createdAt);
        ReflectionTestUtils.setField(booking, "status", status);
        repository.save(booking);
    }
}
