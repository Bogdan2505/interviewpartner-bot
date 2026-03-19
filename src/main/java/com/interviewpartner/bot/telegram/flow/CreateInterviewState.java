package com.interviewpartner.bot.telegram.flow;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;

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
    public InterviewFormat format;
    public LocalDateTime dateTime;
    public Integer durationMinutes;
    /** Список доступных слотов для выбора (шаг VIEW_SLOTS). */
    public List<AvailableSlotDto> availableSlots;

    public enum Step {
        LANGUAGE,
        FORMAT,
        VIEW_SLOTS,
        DATE_TIME,
        DURATION,
        PARTNER,
        CONFIRM
    }
}

