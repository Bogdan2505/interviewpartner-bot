package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;

import java.time.LocalDateTime;
import java.util.List;

public interface InterviewRequestService {
    /**
     * Создаёт заявку на открытый solo-слот: {@code candidateUserId} и {@code interviewerUserId} должны совпадать (владелец слота).
     */
    InterviewRequest createRequest(Long candidateUserId,
                                   Long interviewerUserId,
                                   Language language,
                                   InterviewFormat format,
                                   LocalDateTime dateTime,
                                   int durationMinutes,
                                   Level level);

    List<InterviewRequest> getUserRequests(Long userId, InterviewRequestStatus status);

    List<InterviewRequest> getOpenSoloRequests(Language language, Long excludeUserId, LocalDateTime now);

    InterviewRequest cancel(Long requestId, long actorTelegramId, LocalDateTime now);

    /**
     * Закрывает открытый слот: переводит заявку в ACCEPTED; второй участник создаётся в {@link com.interviewpartner.bot.model.Interview}.
     */
    InterviewRequest completeOpenSlotWithJoiner(Long openSlotRequestId,
                                                Long joinerUserId,
                                                Language language,
                                                InterviewFormat format,
                                                LocalDateTime dateTime,
                                                int durationMinutes,
                                                LocalDateTime now);
}
