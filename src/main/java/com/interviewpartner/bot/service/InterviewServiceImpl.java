package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.UserRepository;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class InterviewServiceImpl implements InterviewService {

    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final ScheduleService scheduleService;

    @Override
    public Interview createInterview(
            Long candidateId,
            Long interviewerId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            int durationMinutes
    ) {
        var candidate = userRepository.findById(candidateId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + candidateId + " not found"));
        var interviewer = userRepository.findById(interviewerId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + interviewerId + " not found"));

        if (!interviewRepository.findConflictingInterviews(candidateId, dateTime, durationMinutes).isEmpty()) {
            throw new InterviewConflictException("Candidate has conflicting interview");
        }
        if (!interviewRepository.findConflictingInterviews(interviewerId, dateTime, durationMinutes).isEmpty()) {
            throw new InterviewConflictException("Interviewer has conflicting interview");
        }

        return interviewRepository.save(Interview.builder()
                .candidate(candidate)
                .interviewer(interviewer)
                .language(language)
                .format(format)
                .dateTime(dateTime)
                .duration(durationMinutes)
                .status(InterviewStatus.SCHEDULED)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAvailablePartners(Long userId, Language language, LocalDateTime dateTime) {
        return userRepository.findByLanguageAndIdNot(language, userId).stream()
                .filter(u -> scheduleService.isUserAvailable(u.getId(), dateTime))
                .filter(u -> interviewRepository.findConflictingInterviews(u.getId(), dateTime, 60).isEmpty())
                .filter(u -> interviewRepository.findConflictingInterviews(userId, dateTime, 60).isEmpty())
                .toList();
    }

    private static final int SLOT_DURATION = 60;
    private static final int MAX_SLOTS_RETURNED = 25;

    @Override
    @Transactional(readOnly = true)
    public List<AvailableSlotDto> getAvailableSlotsAsCandidate(Long candidateUserId, Language language, int daysAhead) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(daysAhead);
        List<User> interviewers = userRepository.findByLanguageAndIdNot(language, candidateUserId);
        List<AvailableSlotDto> out = new ArrayList<>();
        for (User interviewer : interviewers) {
            List<LocalDateTime> starts = scheduleService.getFreeSlotStarts(interviewer.getId(), from, to, SLOT_DURATION);
            for (LocalDateTime start : starts) {
                if (!scheduleService.isUserAvailable(candidateUserId, start)) continue;
                if (interviewRepository.findConflictingInterviews(interviewer.getId(), start, SLOT_DURATION).isEmpty()
                        && interviewRepository.findConflictingInterviews(candidateUserId, start, SLOT_DURATION).isEmpty()) {
                    String label = interviewer.getUsername() != null ? "@" + interviewer.getUsername() : "User " + interviewer.getId();
                    out.add(new AvailableSlotDto(start, interviewer.getId(), label));
                    if (out.size() >= MAX_SLOTS_RETURNED) return out;
                }
            }
        }
        return out.stream().sorted(Comparator.comparing(AvailableSlotDto::dateTime)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableSlotDto> getAvailableSlotsAsInterviewer(Long interviewerUserId, Language language, int daysAhead) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(daysAhead);
        List<User> candidates = userRepository.findByLanguageAndIdNot(language, interviewerUserId);
        List<AvailableSlotDto> out = new ArrayList<>();
        for (User candidate : candidates) {
            List<LocalDateTime> starts = scheduleService.getFreeSlotStarts(candidate.getId(), from, to, SLOT_DURATION);
            for (LocalDateTime start : starts) {
                if (!scheduleService.isUserAvailable(interviewerUserId, start)) continue;
                if (interviewRepository.findConflictingInterviews(candidate.getId(), start, SLOT_DURATION).isEmpty()
                        && interviewRepository.findConflictingInterviews(interviewerUserId, start, SLOT_DURATION).isEmpty()) {
                    String label = candidate.getUsername() != null ? "@" + candidate.getUsername() : "User " + candidate.getId();
                    out.add(new AvailableSlotDto(start, candidate.getId(), label));
                    if (out.size() >= MAX_SLOTS_RETURNED) return out;
                }
            }
        }
        return out.stream().sorted(Comparator.comparing(AvailableSlotDto::dateTime)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Interview> getUserInterviews(Long userId, InterviewStatus status) {
        return interviewRepository.findByUserIdAndOptionalStatus(userId, status);
    }

    @Override
    public Interview cancelInterview(Long interviewId) {
        var interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview with id=" + interviewId + " not found"));
        interview.setStatus(InterviewStatus.CANCELLED);
        return interviewRepository.save(interview);
    }

    @Override
    public Interview completeInterview(Long interviewId) {
        var interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview with id=" + interviewId + " not found"));
        interview.setStatus(InterviewStatus.COMPLETED);
        return interviewRepository.save(interview);
    }
}

