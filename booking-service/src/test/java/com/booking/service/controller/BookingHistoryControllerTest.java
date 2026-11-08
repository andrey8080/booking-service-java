package com.booking.service.controller;

import com.booking.service.dto.response.BookingHistoryResponse;
import com.booking.service.entity.BookingStatus;
import com.booking.service.exception.BusinessException;
import com.booking.service.exception.GlobalExceptionHandler;
import com.booking.service.service.BookingHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookingHistoryControllerTest {

    @Mock
    private BookingHistoryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookingHistoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsHistoryWithRequestedPagination() throws Exception {
        BookingHistoryResponse history = new BookingHistoryResponse(7L, 15L,
                BookingStatus.AWAIT_CONFIRMATION, BookingStatus.CONFIRMED,
                OffsetDateTime.parse("2026-11-04T13:25:47Z"), "Подтверждение каталога", "System");
        when(service.getHistory(15L, 1, 2))
                .thenReturn(new PageImpl<>(List.of(history), PageRequest.of(1, 2), 3));

        mockMvc.perform(get("/api/bookings/15/history").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].bookingId").value(15))
                .andExpect(jsonPath("$.content[0].previousStatus").value("AWAIT_CONFIRMATION"))
                .andExpect(jsonPath("$.content[0].newStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].reason").value("Подтверждение каталога"))
                .andExpect(jsonPath("$.content[0].initiator").value("System"));
        verify(service).getHistory(15L, 1, 2);
    }

    @Test
    void usesDefaultPaginationAndReturnsEmptyHistory() throws Exception {
        when(service.getHistory(15L, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/bookings/15/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));
        verify(service).getHistory(15L, 0, 20);
    }

    @Test
    void returnsBadRequestForInvalidPagination() throws Exception {
        when(service.getHistory(15L, -1, 20)).thenThrow(new BusinessException("Некорректная пагинация"));

        mockMvc.perform(get("/api/bookings/15/history").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }
}
