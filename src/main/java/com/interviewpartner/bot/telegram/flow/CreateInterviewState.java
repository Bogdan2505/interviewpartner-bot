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
    /** Текущий пользователь (кто проходит поток «Записаться»). */
    public Long candidateUserId;
    /** Партнёр; до пары при создании открытого слота совпадает с candidateUserId. */
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
