package com.interviewpartner.bot.service.dto;

import java.time.LocalDateTime;

/**
 * Слот для записи на собеседование: время + партнёр (интервьюер или кандидат).
 */
public record AvailableSlotDto(
        LocalDateTime dateTime,
        Long partnerUserId,
        String partnerLabel
) {}
