package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.CandidateSlot;
import com.interviewpartner.bot.model.Language;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface CandidateSlotRepository extends JpaRepository<CandidateSlot, Long> {
    List<CandidateSlot> findByUserId(Long userId);

    List<CandidateSlot> findByUserIdAndDayOfWeek(Long userId, DayOfWeek dayOfWeek);

    List<CandidateSlot> findByLanguageAndUserIdNot(Language language, Long excludeUserId);
}
