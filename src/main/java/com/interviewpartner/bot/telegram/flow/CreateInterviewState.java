package com.interviewpartner.bot.telegram.flow;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class CreateInterviewState {
    public Step step = Step.LANGUAGE;
    /** true = я кандидат, false = я интервьюер. */
    public boolean asCandidate = true;
    /** Id кандидата (когда asCandidate: это я; иначе — выбранный партнёр). */
    public Long candidateUserId;
    /** Id интервьюера (когда asCandidate: выбранный партнёр; иначе — это я). */
    public Long interviewerUserId;
    public Language language;
    /** Уровень для данного собеседования; null = «не важно». */
    public Level level;
    public InterviewFormat format;
    public LocalDateTime dateTime;
    public Integer durationMinutes;
    /** Список доступных слотов для выбора (шаг VIEW_SLOTS). */
    public List<AvailableSlotDto> availableSlots;
    /** Выбранная дата при просмотре слотов по календарю. */
    public LocalDate selectedSlotDate;
    /** Если выбирается существующий solo-слот — ID интервью для обновления вместо создания нового. */
    public Long joinInterviewId;
    /** Календарь слотов: месяц для отображения. */
    public int slotCalendarYear;
    public int slotCalendarMonth;

    public enum Step {
        LANGUAGE,
        LEVEL,
        FORMAT,
        VIEW_SLOT_DATES,
        VIEW_SLOTS,
        DATE_TIME,
        DURATION,
        PARTNER,
        CONFIRM
    }
}

