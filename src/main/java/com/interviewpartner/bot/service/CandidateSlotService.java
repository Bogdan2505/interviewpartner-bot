package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.CandidateSlot;
import com.interviewpartner.bot.model.Language;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public interface CandidateSlotService {

    CandidateSlot addSlot(Long userId, Language language, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime);

    void removeSlot(Long slotId);

    List<CandidateSlot> getUserSlots(Long userId);

    boolean isUserAvailable(Long userId, LocalDateTime dateTime);

    List<LocalDateTime> getFreeSlotStarts(Long userId, LocalDate from, LocalDate to, int durationMinutes);
}
