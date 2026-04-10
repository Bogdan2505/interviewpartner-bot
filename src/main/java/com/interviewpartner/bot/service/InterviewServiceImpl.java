package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.CandidateSlot;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.ScheduleRepository;
import com.interviewpartner.bot.repository.UserRepository;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

@Slf4j
@Service
@Transactional
public class InterviewServiceImpl implements InterviewService {

    private static final int SLOT_DURATION = 60;

    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;

    private final String jitsiMeetBaseUrl;
    private final Clock clock;

    public InterviewServiceImpl(
            InterviewRepository interviewRepository,
            UserRepository userRepository,
            ScheduleRepository scheduleRepository,
            @Value("${video-meeting.jitsi-base-url:https://meet.jit.si}") String jitsiMeetBaseUrl,
            Clock clock
    ) {
        this.interviewRepository = interviewRepository;
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.jitsiMeetBaseUrl = jitsiMeetBaseUrl.endsWith("/")
                ? jitsiMeetBaseUrl.substring(0, jitsiMeetBaseUrl.length() - 1)
                : jitsiMeetBaseUrl;
        this.clock = clock;
    }

    @Override
    public Interview createInterview(
            Long candidateId,
            Long interviewerId,
            Language language,
            Level level,
            InterviewFormat format,
            LocalDateTime dateTime,
            int durationMinutes,
            boolean initiatorIsCandidate
    ) {
        var candidate = userRepository.findById(candidateId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + candidateId + " not found"));
        var interviewer = userRepository.findById(interviewerId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + interviewerId + " not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        if (dateTime.isBefore(now)) {
            throw new IllegalArgumentException("Interview time must not be in the past");
        }

        if (!interviewRepository.findConflictingInterviews(candidateId, dateTime, durationMinutes).isEmpty()) {
            throw new InterviewConflictException("Candidate has conflicting interview");
        }
        if (!interviewRepository.findConflictingInterviews(interviewerId, dateTime, durationMinutes).isEmpty()) {
            throw new InterviewConflictException("Interviewer has conflicting interview");
        }

        Interview saved = interviewRepository.save(Interview.builder()
                .candidate(candidate)
                .interviewer(interviewer)
                .language(language)
                .level(level)
                .format(format)
                .dateTime(dateTime)
                .duration(durationMinutes)
                .status(InterviewStatus.SCHEDULED)
                .initiatorIsCandidate(initiatorIsCandidate)
                .build());
        log.info("Собеседование создано: id={}, candidateId={}, interviewerId={}, language={}, level={}, time={}, initiatorIsCandidate={}",
                saved.getId(), candidateId, interviewerId, language, level, dateTime, initiatorIsCandidate);
        attachJitsiIfPaired(saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAvailablePartners(Long userId, Language language, LocalDateTime dateTime) {
        return scheduleRepository.findByLanguageAndUserIdNot(language, userId).stream()
                .map(Schedule::getUser)
                .distinct()
                .filter(u -> isInterviewerAvailable(u.getId(), dateTime))
                .filter(u -> interviewRepository.findConflictingInterviews(u.getId(), dateTime, 60).isEmpty())
                .filter(u -> interviewRepository.findConflictingInterviews(userId, dateTime, 60).isEmpty())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableSlotDto> getAvailableSlotsAsCandidate(Long candidateUserId, Language language, Level level, int daysAhead) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Interview> soloInterviewerSlots = interviewRepository.findOpenSoloSlots(
                language, candidateUserId, false, now);

        List<AvailableSlotDto> out = new ArrayList<>();
        for (Interview interview : soloInterviewerSlots) {
            Long interviewerId = interview.getInterviewer().getId();
            if (interviewerId.equals(candidateUserId)) continue;
            // Фильтрация по уровню: если уровень указан, показываем только совпадающие слоты
            if (level != null && !level.equals(interview.getLevel())) continue;
            if (interviewRepository.findConflictingPairedInterviews(candidateUserId, interview.getDateTime(), SLOT_DURATION).isEmpty()) {
                User interviewer = interview.getInterviewer();
                String baseLabel = interviewer.getUsername() != null ? "@" + interviewer.getUsername() : "User " + interviewerId;
                out.add(new AvailableSlotDto(interview.getDateTime(), interviewerId, baseLabel, interview.getId(), interview.getLevel()));
            }
        }

        return out.stream().sorted(Comparator.comparing(AvailableSlotDto::dateTime)).toList();
    }

    @Override
    public Interview joinInterview(Long interviewId, Long userId, boolean asCandidate) {
        var interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        if (asCandidate) {
            interview.setCandidate(user);
        } else {
            interview.setInterviewer(user);
        }
        Interview saved = interviewRepository.save(interview);

        int cancelledSolo = cancelConflictingSoloSlots(userId, saved.getDateTime(), saved.getDuration(), saved.getId());
        log.info("Пользователь присоединился к собеседованию: interviewId={}, userId={}, asCandidate={}, cancelledSoloSlots={}",
                saved.getId(), userId, asCandidate, cancelledSolo);
        attachJitsiIfPaired(saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Interview getInterviewWithParticipants(Long id) {
        return interviewRepository.findByIdWithParticipants(id)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + id));
    }

    private int cancelConflictingSoloSlots(Long userId, LocalDateTime joinedAt, int durationMinutes, Long excludeInterviewId) {
        LocalDateTime joinedEnd = joinedAt.plusMinutes(durationMinutes);
        var toCancel = interviewRepository.findUserInterviewsStartingBefore(userId, joinedEnd).stream()
                .filter(i -> !i.getId().equals(excludeInterviewId))
                .filter(i -> {
                    var end = i.getDateTime().plusMinutes(i.getDuration());
                    return end.isAfter(joinedAt);
                })
                .filter(i -> i.getCandidate() != null && i.getInterviewer() != null
                        && i.getCandidate().getId() != null
                        && i.getCandidate().getId().equals(i.getInterviewer().getId()))
                .toList();
        toCancel.forEach(i -> {
            i.setStatus(InterviewStatus.CANCELLED);
            interviewRepository.save(i);
        });
        return toCancel.size();
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
        Interview saved = interviewRepository.save(interview);
        log.info("Собеседование отменено: interviewId={}", interviewId);
        return saved;
    }

    @Override
    public Interview completeInterview(Long interviewId) {
        var interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview with id=" + interviewId + " not found"));
        interview.setStatus(InterviewStatus.COMPLETED);
        Interview saved = interviewRepository.save(interview);
        log.info("Собеседование завершено: interviewId={}", interviewId);
        return saved;
    }

    // ---- helpers ----

    private void attachJitsiIfPaired(Interview interview) {
        if (interview.getCandidate() == null || interview.getInterviewer() == null) {
            return;
        }
        Long cId = interview.getCandidate().getId();
        Long iId = interview.getInterviewer().getId();
        if (cId == null || iId == null || cId.equals(iId)) {
            return;
        }
        String existing = interview.getVideoMeetingUrl();
        if (existing != null && !existing.isBlank()) {
            return;
        }
        String url = buildJitsiRoomUrl(interview.getId());
        interview.setVideoMeetingUrl(url);
        interviewRepository.save(interview);
        log.info("Ссылка на видеовстречу (Jitsi Meet) задана: interviewId={}", interview.getId());
    }

    private String buildJitsiRoomUrl(long interviewId) {
        return jitsiMeetBaseUrl + "/interviewpartner-" + interviewId;
    }

    private boolean isInterviewerAvailable(Long interviewerId, LocalDateTime dateTime) {
        if (dateTime == null) return false;
        var day = dateTime.getDayOfWeek();
        var time = dateTime.toLocalTime();
        return scheduleRepository.findByUserIdAndDayOfWeek(interviewerId, day).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsAvailable()))
                .anyMatch(s -> !time.isBefore(s.getStartTime()) && time.isBefore(s.getEndTime()));
    }

    private boolean isInterviewerAvailable(Long interviewerId, LocalDateTime dateTime, CandidateSlot cSlot) {
        return scheduleRepository.findByUserIdAndDayOfWeek(interviewerId, cSlot.getDayOfWeek()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsAvailable()))
                .anyMatch(s -> overlapsTime(s.getStartTime(), s.getEndTime(), cSlot.getStartTime(), cSlot.getEndTime()));
    }

    private boolean isInterviewerFreeAt(Long interviewerId, LocalDateTime dateTime) {
        var day = dateTime.getDayOfWeek();
        var time = dateTime.toLocalTime();
        return scheduleRepository.findByUserIdAndDayOfWeek(interviewerId, day).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsAvailable()))
                .anyMatch(s -> !time.isBefore(s.getStartTime()) && time.isBefore(s.getEndTime()));
    }

    private static List<LocalDateTime> getScheduleSlotStarts(Schedule slot, LocalDate from, LocalDate to, int durationMinutes) {
        var result = new TreeSet<LocalDateTime>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != slot.getDayOfWeek() || !Boolean.TRUE.equals(slot.getIsAvailable())) continue;
            LocalTime start = slot.getStartTime();
            LocalTime end = slot.getEndTime();
            if (start.plusMinutes(durationMinutes).isAfter(end)) continue;
            for (LocalTime t = start; t.plusMinutes(durationMinutes).compareTo(end) <= 0; t = t.plusMinutes(60)) {
                result.add(d.atTime(t));
            }
        }
        return new ArrayList<>(result);
    }

    private static List<LocalDateTime> getCandidateSlotStarts(CandidateSlot slot, LocalDate from, LocalDate to, int durationMinutes) {
        var result = new TreeSet<LocalDateTime>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != slot.getDayOfWeek() || !Boolean.TRUE.equals(slot.getIsAvailable())) continue;
            LocalTime start = slot.getStartTime();
            LocalTime end = slot.getEndTime();
            if (start.plusMinutes(durationMinutes).isAfter(end)) continue;
            for (LocalTime t = start; t.plusMinutes(durationMinutes).compareTo(end) <= 0; t = t.plusMinutes(60)) {
                result.add(d.atTime(t));
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean overlapsTime(LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
        return start1.isBefore(end2) && start2.isBefore(end1);
    }
}
