package com.interviewpartner.bot.telegram.flow;

import com.interviewpartner.bot.model.Language;

import java.time.LocalDateTime;

public class FindPartnerState {
    public Long requesterUserId;
    public Step step = Step.LANGUAGE;
    public Language language;
    public LocalDateTime dateTime;

    public enum Step {
        LANGUAGE,
        DATE_TIME
    }
}

