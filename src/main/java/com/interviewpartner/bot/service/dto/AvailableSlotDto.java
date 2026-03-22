package com.interviewpartner.bot.service.dto;

import java.time.LocalDateTime;

/**
 * Слот для записи на собеседование: время + партнёр (интервьюер или кандидат).
 * interviewId != null означает, что слот уже существует в таблице interviews
 * (solo-слот, созданный без партнёра) — нужно обновить его, а не создавать новый.
 */
public record AvailableSlotDto(
        LocalDateTime dateTime,
        Long partnerUserId,
        String partnerLabel,
        Long interviewId
) {
    /** Удобный конструктор для нового слота (не существующего интервью). */
    public AvailableSlotDto(LocalDateTime dateTime, Long partnerUserId, String partnerLabel) {
        this(dateTime, partnerUserId, partnerLabel, null);
    }
}
