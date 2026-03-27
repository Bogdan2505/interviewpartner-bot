package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.repository.InterviewRequestRepository;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InterviewRequestServiceImpl implements InterviewRequestService {

    private final InterviewRequestRepository interviewRequestRepository;
    private final UserRepository userRepository;

    @Override
    public InterviewRequest createRequest(Long candidateUserId,
                                          Long interviewerUserId,
                                          Language language,
                                          InterviewFormat format,
                                          LocalDateTime dateTime,
                                          int durationMinutes) {
        var candidate = userRepository.findById(candidateUserId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + candidateUserId + " not found"));
        var interviewer = userRepository.findById(interviewerUserId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + interviewerUserId + " not found"));

        LocalDateTime now = LocalDateTime.now();
        if (dateTime.isBefore(now)) {
            throw new IllegalArgumentException("Interview request time must not be in the past");
        }

        InterviewRequest saved = interviewRequestRepository.save(InterviewRequest.builder()
                .candidate(candidate)
                .interviewer(interviewer)
                .language(language)
                .format(format)
                .dateTime(dateTime)
                .durationMinutes(durationMinutes)
                .status(InterviewRequestStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .respondedAt(null)
                .build());
        log.info("Создан запрос на собеседование: requestId={}, candidateId={}, interviewerId={}, time={}",
                saved.getId(), candidateUserId, interviewerUserId, dateTime);
        return saved;
    }

    @Override
    public InterviewRequest accept(Long requestId, LocalDateTime now) {
        var req = getPending(requestId);
        req.setStatus(InterviewRequestStatus.ACCEPTED);
        req.setRespondedAt(now);
        InterviewRequest saved = interviewRequestRepository.save(req);
        log.info("Запрос на собеседование принят: requestId={}", requestId);
        return saved;
    }

    @Override
    public InterviewRequest decline(Long requestId, LocalDateTime now) {
        var req = getPending(requestId);
        req.setStatus(InterviewRequestStatus.DECLINED);
        req.setRespondedAt(now);
        InterviewRequest saved = interviewRequestRepository.save(req);
        log.info("Запрос на собеседование отклонён: requestId={}", requestId);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public InterviewRequest getPending(Long requestId) {
        return interviewRequestRepository.findByIdAndStatus(requestId, InterviewRequestStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("Request not found or not pending: id=" + requestId));
    }
}

