package com.interviewpartner.bot.telegram.flow;

import java.time.DayOfWeek;

public class ScheduleState {
    public Long userId;
    public Step step = Step.IDLE;
    public DayOfWeek dayOfWeek;

    public enum Step {
        IDLE,
        ADD_DAY,
        ADD_TIME
    }
}

