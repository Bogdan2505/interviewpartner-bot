INSERT INTO interview_requests (
    candidate_id,
    interviewer_id,
    language,
    format,
    date_time,
    duration_minutes,
    status,
    created_at,
    responded_at
)
SELECT
    i.candidate_id,
    i.interviewer_id,
    i.language,
    i.format,
    i.date_time,
    i.duration_minutes,
    'ACCEPTED',
    i.created_at,
    i.created_at
FROM interviews i
WHERE NOT EXISTS (
    SELECT 1
    FROM interview_requests r
    WHERE r.candidate_id = i.candidate_id
      AND r.interviewer_id = i.interviewer_id
      AND r.language = i.language
      AND r.format = i.format
      AND r.date_time = i.date_time
      AND r.duration_minutes = i.duration_minutes
);
