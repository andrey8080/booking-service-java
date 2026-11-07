package com.booking.service.repository;

import com.booking.service.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean register(UUID eventId) {
        if (eventId == null) {
            throw new BusinessException("Идентификатор события обязателен");
        }
        return jdbcTemplate.update(
                "INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
                eventId) == 1;
    }
}
