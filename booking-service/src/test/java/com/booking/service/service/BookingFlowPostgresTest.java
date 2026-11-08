package com.booking.service.service;

import com.booking.service.config.CurrentDateTimeProvider;
import com.booking.service.dto.response.ResourceBookingCount;
import com.booking.service.entity.BookingStatus;
import com.booking.service.messaging.listener.BookingEventPublisher;
import com.booking.service.repository.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.booking.service.entity.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MENTOR_POSTGRES_URL:jdbc:postgresql://localhost:55438/mentor_practice}",
        "spring.datasource.username=${MENTOR_POSTGRES_USER:mentor}",
        "spring.datasource.password=${MENTOR_POSTGRES_PASSWORD:mentor}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.liquibase.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BookingService.class, BookingHistoryService.class, BookingStatisticsService.class,
        ProcessedEventRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "MENTOR_POSTGRES_TESTS", matches = "true")
class BookingFlowPostgresTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-08T10:37:19Z");

    @Autowired
    private BookingService service;

    @Autowired
    private BookingHistoryService historyService;

    @Autowired
    private BookingStatisticsService statisticsService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private BookingEventPublisher publisher;

    @MockitoBean
    private CurrentDateTimeProvider clock;

    @BeforeEach
    void setUp() {
        clearTestDatabase();
        when(clock.utcNow()).thenReturn(NOW);
    }

    @AfterEach
    void tearDown() {
        clearTestDatabase();
    }

    @Test
    void createsConfirmsRollsBackFailedCancellationAndCompletesCancellationWithHistory() {
        long id = createBooking();
        UUID requestId = requestId(id);
        assertState(id, AWAIT_CONFIRMATION);

        service.handleBookingJobConfirmed(UUID.randomUUID(), requestId);
        assertState(id, CONFIRMED);
        service.cancelBooking(id);
        assertPending(id, CONFIRMED);
        var pendingStatistics = statisticsService.getStatistics(NOW.toLocalDate(), NOW.toLocalDate());
        assertThat(pendingStatistics.bookingsByStatus()).containsEntry(CANCELLATION_PENDING, 1L);

        service.handleError(UUID.randomUUID(), requestId);
        assertState(id, CONFIRMED);
        service.cancelBooking(id);
        service.handleBookingJobDenied(UUID.randomUUID(), requestId);
        assertState(id, CANCELLED);

        assertHistory(id,
                new History(null, "AWAIT_CONFIRMATION", "42"),
                new History("AWAIT_CONFIRMATION", "CONFIRMED", "System"),
                new History("CONFIRMED", "CANCELLATION_PENDING", "42"),
                new History("CANCELLATION_PENDING", "CONFIRMED", "System"),
                new History("CONFIRMED", "CANCELLATION_PENDING", "42"),
                new History("CANCELLATION_PENDING", "CANCELLED", "System"));
        var page = historyService.getHistory(id, 0, 2);
        assertThat(page.getTotalElements()).isEqualTo(6);
        assertThat(page.getContent()).extracting(item -> item.newStatus())
                .containsExactly(CANCELLED, CANCELLATION_PENDING);
        var statistics = statisticsService.getStatistics(NOW.toLocalDate(), NOW.toLocalDate());
        assertThat(statistics.totalBookings()).isEqualTo(1);
        assertThat(statistics.bookingsByStatus()).hasSize(BookingStatus.values().length)
                .containsEntry(CANCELLED, 1L).containsEntry(CANCELLATION_PENDING, 0L);
        assertThat(statistics.topResources()).containsExactly(new ResourceBookingCount(7L, 1));
        verify(publisher).publishCreateBookingJob(any());
        verify(publisher, times(2)).publishCancelBookingJob(any());
    }

    @Test
    void confirmationWinsWhenItArrivesDuringPendingCancellation() {
        long id = createBooking();
        service.cancelBooking(id);
        assertPending(id, AWAIT_CONFIRMATION);

        service.handleBookingJobConfirmed(UUID.randomUUID(), requestId(id));

        assertState(id, CONFIRMED);
        assertHistory(id,
                new History(null, "AWAIT_CONFIRMATION", "42"),
                new History("AWAIT_CONFIRMATION", "CANCELLATION_PENDING", "42"),
                new History("CANCELLATION_PENDING", "CONFIRMED", "System"));
    }

    @Test
    void ignoresLateCancellationErrorsAfterConfirmationOrCompletedCancellation() {
        long confirmedId = createBooking();
        service.cancelBooking(confirmedId);
        service.handleBookingJobConfirmed(UUID.randomUUID(), requestId(confirmedId));
        UUID confirmedErrorId = UUID.randomUUID();
        service.handleError(confirmedErrorId, requestId(confirmedId));
        assertState(confirmedId, CONFIRMED);
        assertThat(history(confirmedId)).hasSize(3);
        assertThat(markerCount(confirmedErrorId)).isEqualTo(1);

        long cancelledId = createBooking();
        service.cancelBooking(cancelledId);
        service.handleBookingJobDenied(UUID.randomUUID(), requestId(cancelledId));
        UUID cancelledErrorId = UUID.randomUUID();
        service.handleError(cancelledErrorId, requestId(cancelledId));
        assertState(cancelledId, CANCELLED);
        assertThat(history(cancelledId)).hasSize(3);
        assertThat(markerCount(cancelledErrorId)).isEqualTo(1);
    }

    @Test
    void ignoresLateDenialFromAnotherCancellationRetry() {
        long id = createBooking();
        service.cancelBooking(id);
        service.handleBookingJobDenied(UUID.randomUUID(), requestId(id));
        UUID lateEventId = UUID.randomUUID();

        service.handleBookingJobDenied(lateEventId, requestId(id));

        assertState(id, CANCELLED);
        assertThat(history(id)).hasSize(3);
        assertThat(markerCount(lateEventId)).isEqualTo(1);
    }

    @Test
    void rollsBackBookingHistoryAndProcessedMarkerTogetherAndAllowsRetry() {
        long id = createBooking();
        UUID requestId = requestId(id);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.handleBookingJobConfirmed(eventId, requestId);
            entityManager.flush();
            assertThat(markerCount(eventId)).isEqualTo(1);
            assertThat(history(id)).hasSize(2);
            assertState(id, CONFIRMED);
            throw new RollbackProbe();
        })).isInstanceOf(RollbackProbe.class);

        assertState(id, AWAIT_CONFIRMATION);
        assertThat(markerCount(eventId)).isZero();
        assertHistory(id, new History(null, "AWAIT_CONFIRMATION", "42"));

        service.handleBookingJobConfirmed(eventId, requestId);

        assertState(id, CONFIRMED);
        assertThat(markerCount(eventId)).isEqualTo(1);
        assertThat(history(id)).hasSize(2);
    }

    @Test
    void ignoresDuplicateConfirmation() {
        long id = createBooking();
        UUID eventId = UUID.randomUUID();
        UUID requestId = requestId(id);

        service.handleBookingJobConfirmed(eventId, requestId);
        service.handleBookingJobConfirmed(eventId, requestId);

        assertState(id, CONFIRMED);
        assertThat(history(id)).hasSize(2);
        assertThat(markerCount(eventId)).isEqualTo(1);
    }

    @Test
    void ignoresDuplicateDenial() {
        long id = createBooking();
        UUID eventId = UUID.randomUUID();
        UUID requestId = requestId(id);

        service.handleBookingJobDenied(eventId, requestId);
        service.handleBookingJobDenied(eventId, requestId);

        assertState(id, CANCELLED);
        assertThat(history(id)).hasSize(2);
        assertThat(markerCount(eventId)).isEqualTo(1);
    }

    @Test
    void ignoresDuplicateDlqError() {
        long id = createBooking();
        UUID eventId = UUID.randomUUID();
        UUID requestId = requestId(id);
        service.cancelBooking(id);

        service.handleError(eventId, requestId);
        service.handleError(eventId, requestId);

        assertState(id, AWAIT_CONFIRMATION);
        assertThat(history(id)).hasSize(3);
        assertThat(markerCount(eventId)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateWaitsForCommitAndDoesNotRepeatHistory() throws Exception {
        runConcurrentDuplicate(false);
    }

    @Test
    void concurrentDuplicateProcessesEventAfterFirstTransactionRollsBack() throws Exception {
        runConcurrentDuplicate(true);
    }

    private void runConcurrentDuplicate(boolean rollbackFirst) throws Exception {
        long id = createBooking();
        UUID eventId = UUID.randomUUID();
        UUID requestId = requestId(id);
        var firstProcessed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        service.handleBookingJobConfirmed(eventId, requestId);
                        entityManager.flush();
                        firstProcessed.countDown();
                        await(releaseFirst);
                        if (rollbackFirst) {
                            throw new RollbackProbe();
                        }
                    });
                } catch (RollbackProbe ex) {
                    assertThat(rollbackFirst).isTrue();
                }
            });
            await(firstProcessed);
            var second = executor.submit(() -> service.handleBookingJobConfirmed(eventId, requestId));
            awaitBlockedDuplicateInsert();
            releaseFirst.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertState(id, CONFIRMED);
        assertThat(markerCount(eventId)).isEqualTo(1);
        assertHistory(id,
                new History(null, "AWAIT_CONFIRMATION", "42"),
                new History("AWAIT_CONFIRMATION", "CONFIRMED", "System"));
    }

    private void awaitBlockedDuplicateInsert() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Integer blocked = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock' AND wait_event = 'transactionid'
                      AND LOWER(query) LIKE '%insert into processed_events%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Concurrent event insert did not wait for the first transaction");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private long createBooking() {
        return service.createBooking(42L, 7L, NOW.toLocalDate().plusDays(2), NOW.toLocalDate().plusDays(3));
    }

    private UUID requestId(long id) {
        return service.getById(id).getCatalogRequestId();
    }

    private void assertState(long id, BookingStatus status) {
        var booking = service.getById(id);
        assertThat(booking.getStatus()).isEqualTo(status);
        assertThat(booking.getPreviousStatus()).isNull();
        assertThat(booking.getCancellationRequestedAt()).isNull();
    }

    private void assertPending(long id, BookingStatus previousStatus) {
        var booking = service.getById(id);
        assertThat(booking.getStatus()).isEqualTo(CANCELLATION_PENDING);
        assertThat(booking.getPreviousStatus()).isEqualTo(previousStatus);
        assertThat(booking.getCancellationRequestedAt()).isEqualTo(NOW);
    }

    private int markerCount(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private List<History> history(long id) {
        return jdbc.query("""
                SELECT previous_status, new_status, initiator FROM booking_history WHERE booking_id = ? ORDER BY id
                """, (row, index) -> new History(row.getString(1), row.getString(2), row.getString(3)), id);
    }

    private void assertHistory(long id, History... expected) {
        assertThat(history(id)).containsExactly(expected);
        assertThat(jdbc.queryForList("SELECT reason FROM booking_history WHERE booking_id = ?", String.class, id))
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    private void clearTestDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("mentor_practice");
        jdbc.execute("TRUNCATE TABLE booking_history, processed_events, bookings RESTART IDENTITY");
    }

    private record History(String previousStatus, String newStatus, String initiator) {
    }

    private static class RollbackProbe extends RuntimeException {
    }
}
