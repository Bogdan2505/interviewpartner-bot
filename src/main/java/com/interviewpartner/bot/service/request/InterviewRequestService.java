package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;

import java.time.LocalDateTime;

public interface InterviewRequestService {
    InterviewRequest createRequest(Long candidateUserId,
                                   Long interviewerUserId,
                                   Language language,
                                   InterviewFormat format,
                                   LocalDateTime dateTime,
                                   int durationMinutes);

    InterviewRequest accept(Long requestId, LocalDateTime now);

    InterviewRequest decline(Long requestId, LocalDateTime now);

    InterviewRequest getPending(Long requestId);
}

