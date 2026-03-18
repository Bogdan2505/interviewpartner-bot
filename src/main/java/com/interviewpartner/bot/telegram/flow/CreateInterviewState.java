package com.interviewpartner.bot.telegram.flow;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;

import java.time.LocalDateTime;

public class CreateInterviewState {
    public Step step = Step.LANGUAGE;
    /** Id пользователя, который создаёт собеседование (candidate). */
    public Long candidateUserId;
    /** Id выбранного партнёра (interviewer). */
    public Long interviewerUserId;
    public Language language;
    public InterviewFormat format;
    public LocalDateTime dateTime;
    public Integer durationMinutes;

    public enum Step {
        LANGUAGE,
        FORMAT,
        DATE_TIME,
        DURATION,
        PARTNER,
        CONFIRM
    }
}

