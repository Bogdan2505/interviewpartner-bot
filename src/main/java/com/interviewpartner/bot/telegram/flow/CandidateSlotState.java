package com.interviewpartner.bot.telegram.flow;

import com.interviewpartner.bot.model.Language;

import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.Set;

public class CandidateSlotState {
    public Long userId;
    public Language language;
    public Step step = Step.IDLE;
    public DayOfWeek dayOfWeek;
    public int calendarYear;
    public int calendarMonth;
    public Set<DayOfWeek> selectedDaysForSlot = new HashSet<>();

    public enum Step {
        IDLE,
        ADD_DAY,
        ADD_TIME,
        CALENDAR
    }
}
