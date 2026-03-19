package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.repository.InterviewRequestRepository;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

        return interviewRequestRepository.save(InterviewRequest.builder()
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
    }

    @Override
    public InterviewRequest accept(Long requestId, LocalDateTime now) {
        var req = getPending(requestId);
        req.setStatus(InterviewRequestStatus.ACCEPTED);
        req.setRespondedAt(now);
        return interviewRequestRepository.save(req);
    }

    @Override
    public InterviewRequest decline(Long requestId, LocalDateTime now) {
        var req = getPending(requestId);
        req.setStatus(InterviewRequestStatus.DECLINED);
        req.setRespondedAt(now);
        return interviewRequestRepository.save(req);
    }

    @Override
    @Transactional(readOnly = true)
    public InterviewRequest getPending(Long requestId) {
        return interviewRequestRepository.findByIdAndStatus(requestId, InterviewRequestStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("Request not found or not pending: id=" + requestId));
    }
}

