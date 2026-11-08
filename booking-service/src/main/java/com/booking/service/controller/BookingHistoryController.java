package com.booking.service.controller;

import com.booking.service.dto.response.BookingHistoryResponse;
import com.booking.service.service.BookingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/{id}/history")
@RequiredArgsConstructor
public class BookingHistoryController {

    private final BookingHistoryService bookingHistoryService;

    @GetMapping
    public Page<BookingHistoryResponse> getHistory(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return bookingHistoryService.getHistory(id, page, size);
    }
}
