package com.booking.service.controller;

import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.GlobalExceptionHandler;
import com.booking.service.repository.BookingRepository;
import com.booking.service.service.BookingStatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingStatisticsControllerTest {

    private BookingRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(BookingRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new BookingStatisticsController(new BookingStatisticsService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsStatisticsWithEveryStatus() throws Exception {
        mvc.perform(get("/api/booking/statistics")
                        .param("dateFrom", "2026-10-26")
                        .param("dateTo", "2026-11-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBookings").value(0))
                .andExpect(jsonPath("$.bookingsByStatus", aMapWithSize(BookingStatus.values().length)))
                .andExpect(jsonPath("$.bookingsByStatus.CONFIRMED").value(0))
                .andExpect(jsonPath("$.topResources").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({",2026-11-08", "2026-10-26,", ",", "invalid,2026-11-08",
            "2026-10-26,08.11.2026", "2026-10-26,2026-02-30", "2026-11-08,2026-10-26"})
    void rejectsMissingMalformedOrReversedDates(String from, String to) throws Exception {
        var request = get("/api/booking/statistics");
        if (from != null) {
            request.param("dateFrom", from);
        }
        if (to != null) {
            request.param("dateTo", to);
        }

        mvc.perform(request).andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
    }
}
