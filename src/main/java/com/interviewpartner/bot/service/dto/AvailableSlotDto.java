package com.interviewpartner.bot.service.dto;

import com.interviewpartner.bot.model.Level;

import java.time.LocalDateTime;

/**
 * Слот для записи на взаимный час: время + владелец открытого окна.
 * {@code openSlotRequestId} — id строки {@code interview_requests} (solo PENDING, partner is null), не id из {@code interviews}.
 */
public record AvailableSlotDto(
        LocalDateTime dateTime,
        Long partnerUserId,
        String partnerLabel,
        Long openSlotRequestId,
        Level partnerLevel
) {
    public AvailableSlotDto(LocalDateTime dateTime, Long partnerUserId, String partnerLabel, Long openSlotRequestId) {
        this(dateTime, partnerUserId, partnerLabel, openSlotRequestId, null);
    }

    /** Слот без привязки к существующей заявке (не используется в текущем списке открытых слотов). */
    public AvailableSlotDto(LocalDateTime dateTime, Long partnerUserId, String partnerLabel) {
        this(dateTime, partnerUserId, partnerLabel, null, null);
    }
}
