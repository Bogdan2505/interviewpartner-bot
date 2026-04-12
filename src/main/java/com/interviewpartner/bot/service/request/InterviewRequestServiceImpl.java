package com.interviewpartner.bot.service.request;

import com.interviewpartner.bot.exception.InterviewRequestForbiddenException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.InterviewRequestRepository;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InterviewRequestServiceImpl implements InterviewRequestService {

    private final InterviewRequestRepository interviewRequestRepository;
    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    public InterviewRequest createRequest(Long candidateUserId,
                                          Long interviewerUserId,
                                          Language language,
                                          InterviewFormat format,
                                          LocalDateTime dateTime,
                                          int durationMinutes,
                                          Level level) {
        if (!candidateUserId.equals(interviewerUserId)) {
            throw new IllegalArgumentException(
                    "Interview request supports only open solo slots; slot owner ids must match");
        }
        var owner = userRepository.findById(candidateUserId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + candidateUserId + " not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        if (dateTime.isBefore(now)) {
            throw new IllegalArgumentException("Interview request time must not be in the past");
        }

        if (!interviewRepository.findConflictingInterviews(candidateUserId, dateTime, durationMinutes).isEmpty()) {
            throw new IllegalArgumentException("Candidate has conflicting interview");
        }
        boolean dup = interviewRequestRepository
                .existsBySlotOwnerIdAndLanguageAndFormatAndDateTimeAndDurationMinutesAndStatusAndLevel(
                        candidateUserId, language, format, dateTime, durationMinutes, InterviewRequestStatus.PENDING, level);
        if (dup) {
            throw new IllegalArgumentException("Duplicate pending interview request");
        }

        InterviewRequest saved = interviewRequestRepository.save(InterviewRequest.builder()
                .slotOwner(owner)
                .language(language)
                .level(level)
                .format(format)
                .dateTime(dateTime)
                .durationMinutes(durationMinutes)
                .status(InterviewRequestStatus.PENDING)
                .createdAt(LocalDateTime.now(clock))
                .respondedAt(null)
                .build());
        log.info("Создан запрос на открытый слот: requestId={}, slotOwnerId={}, time={}",
                saved.getId(),
                owner.getId(),
                dateTime);
        return saved;
    }

    @Override
    public InterviewRequest completeOpenSlotWithJoiner(Long openSlotRequestId,
                                                       Long joinerUserId,
                                                       Language language,
                                                       InterviewFormat format,
                                                       LocalDateTime dateTime,
                                                       int durationMinutes,
                                                       LocalDateTime now) {
        InterviewRequest req = interviewRequestRepository.findById(openSlotRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: id=" + openSlotRequestId));
        if (req.getStatus() != InterviewRequestStatus.PENDING) {
            throw new IllegalArgumentException("Request not found or not pending: id=" + openSlotRequestId);
        }
        if (req.getSlotOwner().getId().equals(joinerUserId)) {
            throw new IllegalArgumentException("Cannot book own open slot");
        }
        if (req.getLanguage() != language || req.getFormat() != format
                || !req.getDateTime().equals(dateTime)
                || !req.getDurationMinutes().equals(durationMinutes)) {
            throw new IllegalArgumentException("Open slot parameters mismatch");
        }
        if (dateTime.isBefore(now)) {
            throw new IllegalArgumentException("Interview request time must not be in the past");
        }
        userRepository.findById(joinerUserId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + joinerUserId + " not found"));
        Long ownerId = req.getSlotOwner().getId();
        if (!interviewRepository.findConflictingInterviews(joinerUserId, dateTime, durationMinutes).isEmpty()) {
            throw new IllegalArgumentException("Candidate has conflicting interview");
        }
        if (!interviewRepository.findConflictingInterviews(ownerId, dateTime, durationMinutes).isEmpty()) {
            throw new IllegalArgumentException("Interviewer has conflicting interview");
        }
        req.setStatus(InterviewRequestStatus.ACCEPTED);
        req.setRespondedAt(now);
        InterviewRequest saved = interviewRequestRepository.save(req);
        log.info("Открытый слот закрыт записью партнёра: requestId={}, joinerId={}, ownerId={}",
                saved.getId(), joinerUserId, ownerId);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterviewRequest> getUserRequests(Long userId, InterviewRequestStatus status) {
        return interviewRequestRepository.findByUserIdAndOptionalStatus(userId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterviewRequest> getOpenSoloRequests(Language language, Long excludeUserId, LocalDateTime now) {
        return interviewRequestRepository.findOpenSoloRequests(language, excludeUserId, now);
    }

    @Override
    public InterviewRequest cancel(Long requestId, long actorTelegramId, LocalDateTime now) {
        var req = interviewRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found: id=" + requestId));
        Long ownerTg = req.getSlotOwner() != null ? req.getSlotOwner().getTelegramId() : null;
        boolean allowed = Objects.equals(ownerTg, actorTelegramId)
                || isBookedJoinerOnFormerOpenSlot(req, actorTelegramId);
        if (!allowed) {
            throw new InterviewRequestForbiddenException();
        }
        if (req.getStatus() == InterviewRequestStatus.DECLINED || req.getStatus() == InterviewRequestStatus.CANCELLED) {
            throw new IllegalArgumentException("Request already closed: id=" + requestId);
        }
        req.setStatus(InterviewRequestStatus.CANCELLED);
        req.setRespondedAt(now);
        InterviewRequest saved = interviewRequestRepository.save(req);
        log.info("Запрос на собеседование отменён: requestId={}, actorTelegramId={}", requestId, actorTelegramId);
        return saved;
    }

    private boolean isBookedJoinerOnFormerOpenSlot(InterviewRequest req, long actorTelegramId) {
        if (req.getSlotOwner() == null) {
            return false;
        }
        if (req.getStatus() != InterviewRequestStatus.ACCEPTED) {
            return false;
        }
        Long ownerId = req.getSlotOwner().getId();
        return interviewRepository.findByUserIdAndOptionalStatus(ownerId, InterviewStatus.SCHEDULED).stream()
                .anyMatch(i -> i.getDateTime().equals(req.getDateTime())
                        && Objects.equals(i.getDuration(), req.getDurationMinutes())
                        && i.getCandidate() != null
                        && i.getInterviewer() != null
                        && Objects.equals(i.getInterviewer().getId(), ownerId)
                        && Objects.equals(i.getCandidate().getTelegramId(), actorTelegramId));
    }
}
