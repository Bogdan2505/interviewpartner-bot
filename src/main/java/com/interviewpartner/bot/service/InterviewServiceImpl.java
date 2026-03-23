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
import com.interviewpartner.bot.repository.CandidateSlotRepository;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.ScheduleRepository;
import com.interviewpartner.bot.repository.UserRepository;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional
public class InterviewServiceImpl implements InterviewService {

    private static final int SLOT_DURATION = 60;
    private static final int MAX_SLOTS_RETURNED = 25;
    private static final int DAYS_AHEAD = 30;

    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final CandidateSlotRepository candidateSlotRepository;

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
                .level(level)
                .format(format)
                .dateTime(dateTime)
                .duration(durationMinutes)
                .status(InterviewStatus.SCHEDULED)
                .initiatorIsCandidate(initiatorIsCandidate)
                .build());
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
        LocalDateTime now = LocalDateTime.now();

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
                if (out.size() >= MAX_SLOTS_RETURNED) break;
            }
        }

        return out.stream().sorted(Comparator.comparing(AvailableSlotDto::dateTime)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableSlotDto> getAvailableSlotsAsInterviewer(Long interviewerUserId, Language language, Level level, int daysAhead) {
        LocalDateTime now = LocalDateTime.now();

        List<Interview> soloCandidateSlots = interviewRepository.findOpenSoloSlots(
                language, interviewerUserId, true, now);

        List<AvailableSlotDto> out = new ArrayList<>();
        for (Interview interview : soloCandidateSlots) {
            Long candidateId = interview.getCandidate().getId();
            if (candidateId.equals(interviewerUserId)) continue;
            // Фильтрация по уровню: если уровень указан, показываем только совпадающие слоты
            if (level != null && !level.equals(interview.getLevel())) continue;
            if (interviewRepository.findConflictingPairedInterviews(interviewerUserId, interview.getDateTime(), SLOT_DURATION).isEmpty()) {
                User candidate = interview.getCandidate();
                String baseLabel = candidate.getUsername() != null ? "@" + candidate.getUsername() : "User " + candidateId;
                out.add(new AvailableSlotDto(interview.getDateTime(), candidateId, baseLabel, interview.getId(), interview.getLevel()));
                if (out.size() >= MAX_SLOTS_RETURNED) break;
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

        // Отменяем собственные solo-слоты пользователя в это же время (они уже не нужны)
        cancelConflictingSoloSlots(userId, saved.getDateTime(), saved.getDuration(), saved.getId());

        return saved;
    }

    private void cancelConflictingSoloSlots(Long userId, LocalDateTime joinedAt, int durationMinutes, Long excludeInterviewId) {
        LocalDateTime joinedEnd = joinedAt.plusMinutes(durationMinutes);
        interviewRepository.findUserInterviewsStartingBefore(userId, joinedEnd).stream()
                .filter(i -> !i.getId().equals(excludeInterviewId))
                .filter(i -> {
                    var end = i.getDateTime().plusMinutes(i.getDuration());
                    return end.isAfter(joinedAt);
                })
                .filter(i -> i.getCandidate() != null && i.getInterviewer() != null
                        && i.getCandidate().getId() != null
                        && i.getCandidate().getId().equals(i.getInterviewer().getId()))
                .forEach(i -> {
                    i.setStatus(InterviewStatus.CANCELLED);
                    interviewRepository.save(i);
                });
    }

    @Override
    public List<Interview> tryAutoMatchForInterviewer(Long interviewerUserId, Language language) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(DAYS_AHEAD);

        List<Schedule> mySlots = scheduleRepository.findByUserId(interviewerUserId).stream()
                .filter(s -> language.equals(s.getLanguage()) && Boolean.TRUE.equals(s.getIsAvailable()))
                .toList();

        List<CandidateSlot> candidateSlots = candidateSlotRepository.findByLanguageAndUserIdNot(language, interviewerUserId);

        List<Interview> created = new ArrayList<>();
        for (Schedule mySlot : mySlots) {
            for (CandidateSlot cSlot : candidateSlots) {
                if (!overlapsTime(mySlot.getStartTime(), mySlot.getEndTime(), cSlot.getStartTime(), cSlot.getEndTime())) continue;
                if (mySlot.getDayOfWeek() != cSlot.getDayOfWeek()) continue;

                LocalTime start = mySlot.getStartTime().isAfter(cSlot.getStartTime()) ? mySlot.getStartTime() : cSlot.getStartTime();
                Long candidateId = cSlot.getUser().getId();

                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != mySlot.getDayOfWeek()) continue;
                    LocalDateTime dateTime = d.atTime(start);
                    if (interviewRepository.findConflictingInterviews(interviewerUserId, dateTime, SLOT_DURATION).isEmpty()
                            && interviewRepository.findConflictingInterviews(candidateId, dateTime, SLOT_DURATION).isEmpty()) {
                        try {
                            Interview interview = createInterview(candidateId, interviewerUserId, language,
                                    null, InterviewFormat.TECHNICAL, dateTime, SLOT_DURATION, false);
                            created.add(interview);
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                }
                if (!created.isEmpty()) return created;
            }
        }
        return created;
    }

    @Override
    public List<Interview> tryAutoMatchForCandidate(Long candidateUserId, Language language) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(DAYS_AHEAD);

        List<CandidateSlot> mySlots = candidateSlotRepository.findByUserId(candidateUserId).stream()
                .filter(s -> language.equals(s.getLanguage()) && Boolean.TRUE.equals(s.getIsAvailable()))
                .toList();

        List<Schedule> interviewerSlots = scheduleRepository.findByLanguageAndUserIdNot(language, candidateUserId);

        List<Interview> created = new ArrayList<>();
        for (CandidateSlot mySlot : mySlots) {
            for (Schedule iSlot : interviewerSlots) {
                if (!overlapsTime(mySlot.getStartTime(), mySlot.getEndTime(), iSlot.getStartTime(), iSlot.getEndTime())) continue;
                if (mySlot.getDayOfWeek() != iSlot.getDayOfWeek()) continue;

                LocalTime start = mySlot.getStartTime().isAfter(iSlot.getStartTime()) ? mySlot.getStartTime() : iSlot.getStartTime();
                Long interviewerId = iSlot.getUser().getId();

                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != mySlot.getDayOfWeek()) continue;
                    LocalDateTime dateTime = d.atTime(start);
                    if (interviewRepository.findConflictingInterviews(interviewerId, dateTime, SLOT_DURATION).isEmpty()
                            && interviewRepository.findConflictingInterviews(candidateUserId, dateTime, SLOT_DURATION).isEmpty()) {
                        try {
                            Interview interview = createInterview(candidateUserId, interviewerId, language,
                                    null, InterviewFormat.TECHNICAL, dateTime, SLOT_DURATION, true);
                            created.add(interview);
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                }
                if (!created.isEmpty()) return created;
            }
        }
        return created;
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

    // ---- helpers ----

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
