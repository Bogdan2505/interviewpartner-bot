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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class InterviewServiceImpl implements InterviewService {

    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;

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
        // MVP: фильтрация только по языку, без расписания. Расписание подключим в ScheduleService/подборе партнёра.
        return userRepository.findByLanguageAndIdNot(language, userId);
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

