package com.interviewpartner.bot.telegram.flow;

import lombok.Getter;

import java.time.LocalDate;

/**
 * Состояние для календаря запланированных собеседований в команде /schedule.
 * Хранится в памяти (MVP).
 */
@Getter
public class InterviewCalendarState {

    /** Разделы расписания. */
    public enum ScheduleFilter {
        /** Заявки — solo-слоты (кандидат = интервьюер), ожидающие партнёра. */
        PENDING,
        /** Согласованные — парные интервью, оба участника подтверждены. */
        CONFIRMED
    }

    public Long userId;

    /** Текущий год/месяц календаря (1-12). */
    public int calendarYear;
    public int calendarMonth;

    /** Если пользователь открыл конкретный день. */
    public LocalDate selectedDate;

    /** Активный раздел расписания (null — ещё не выбран). */
    public ScheduleFilter filter;
}

