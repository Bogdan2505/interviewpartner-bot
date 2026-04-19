CREATE INDEX IF NOT EXISTS idx_interviews_status_date_time
    ON interviews (status, date_time);

CREATE INDEX IF NOT EXISTS idx_interviews_candidate_date_time
    ON interviews (candidate_id, date_time);

CREATE INDEX IF NOT EXISTS idx_interviews_interviewer_date_time
    ON interviews (interviewer_id, date_time);

CREATE INDEX IF NOT EXISTS idx_interview_requests_owner_status_date_time
    ON interview_requests (owner_user_id, status, date_time);
