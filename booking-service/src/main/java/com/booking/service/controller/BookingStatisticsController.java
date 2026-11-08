package com.booking.service.controller;

import com.booking.service.dto.response.BookingStatisticsResponse;
import com.booking.service.service.BookingStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingStatisticsController {

    private final BookingStatisticsService statisticsService;

    @GetMapping("statistics")
    public BookingStatisticsResponse getStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return statisticsService.getStatistics(dateFrom, dateTo);
    }
}
