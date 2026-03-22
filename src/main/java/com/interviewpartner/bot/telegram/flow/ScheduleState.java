package com.interviewpartner.bot.telegram.flow;

import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.Set;

public class ScheduleState {
    public Long userId;
    public Step step = Step.IDLE;
    public DayOfWeek dayOfWeek;
    /** Для календаря: год и месяц (1–12). */
    public int calendarYear;
    public int calendarMonth;
    /** Выбранные дни в календаре (тыкаешь — отмечаешь/снимаешь). */
    public Set<DayOfWeek> selectedDaysForSlot = new HashSet<>();

    public enum Step {
        IDLE,
        ADD_DAY,
        ADD_TIME,
        CALENDAR
    }
}

