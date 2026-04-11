package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;

import java.time.LocalDateTime;
import java.util.List;

public interface InterviewRequestService {
    InterviewRequest createRequest(Long candidateUserId,
                                   Long interviewerUserId,
                                   Language language,
                                   InterviewFormat format,
                                   LocalDateTime dateTime,
                                   int durationMinutes,
                                   Level level);

    InterviewRequest accept(Long requestId, long interviewerTelegramId, LocalDateTime now);

    InterviewRequest decline(Long requestId, long interviewerTelegramId, LocalDateTime now);

    InterviewRequest getPending(Long requestId);

    List<InterviewRequest> getUserRequests(Long userId, InterviewRequestStatus status);

    List<InterviewRequest> getOpenSoloRequests(Language language, Long excludeUserId, LocalDateTime now);

    InterviewRequest cancel(Long requestId, long actorTelegramId, LocalDateTime now);

    /**
     * Закрывает открытый solo-слот (candidate was null): переводит в ACCEPTED; второй участник создаётся в {@link com.interviewpartner.bot.model.Interview}.
     */
    InterviewRequest completeOpenSlotWithJoiner(Long openSlotRequestId,
                                                Long joinerUserId,
                                                Language language,
                                                InterviewFormat format,
                                                LocalDateTime dateTime,
                                                int durationMinutes,
                                                LocalDateTime now);
}

