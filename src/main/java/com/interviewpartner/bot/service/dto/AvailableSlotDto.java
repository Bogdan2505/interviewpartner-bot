package com.interviewpartner.bot.service.dto;

import com.interviewpartner.bot.model.Level;

import java.time.LocalDateTime;

/**
 * Слот для записи на взаимный час: время + создатель открытого окна (в БД — интервьюер первой половины после пары).
 * interviewId != null означает, что слот уже существует в таблице interviews
 * (solo-слот, созданный без партнёра) — нужно обновить его, а не создавать новый.
 * partnerLevel — уровень партнёра (null если не указан).
 */
public record AvailableSlotDto(
        LocalDateTime dateTime,
        Long partnerUserId,
        String partnerLabel,
        Long interviewId,
        Level partnerLevel
) {
    public AvailableSlotDto(LocalDateTime dateTime, Long partnerUserId, String partnerLabel, Long interviewId) {
        this(dateTime, partnerUserId, partnerLabel, interviewId, null);
    }

    /** Удобный конструктор для нового слота (не существующего интервью). */
    public AvailableSlotDto(LocalDateTime dateTime, Long partnerUserId, String partnerLabel) {
        this(dateTime, partnerUserId, partnerLabel, null, null);
    }
}
